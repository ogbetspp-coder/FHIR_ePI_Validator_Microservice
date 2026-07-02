package com.epi.validator.engine;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.util.VersionUtil;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationOptions;
import com.epi.validator.config.EpiValidationProperties;
import com.epi.validator.model.ValidatorInfo;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.UnknownCodeSystemWarningValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.r5.utils.validation.constants.BestPracticeWarningLevel;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds THE validation chain: the official HAPI/HL7 validator engine over the vendored,
 * SHA-256-pinned ePI IG package. Fully offline — no network at build or runtime.
 */
@Configuration(proxyBeanMethods = false)
public class ValidatorFactory {

    private static final Logger log = LoggerFactory.getLogger(ValidatorFactory.class);
    static final String FHIR_VERSION = "5.0.0";

    @Bean
    public FhirValidator fhirValidator(FhirContext fhirContext, EpiValidationProperties properties)
            throws IOException {
        long start = System.currentTimeMillis();
        NpmPackageValidationSupport npm = new NpmPackageValidationSupport(fhirContext);
        for (String location : packageLocations(properties)) {
            npm.loadPackageFromClasspath("classpath:" + location);
        }

        // ePI content references code systems with no distributable offline representation
        // (EDQM Standard Terms, MedDRA, WHO ATC, EMA SPOR). Downgrade unknown code systems to
        // warnings or offline validation would hard-fail every real-world ePI.
        UnknownCodeSystemWarningValidationSupport unknownCodeSystems =
                new UnknownCodeSystemWarningValidationSupport(fhirContext);
        unknownCodeSystems.setNonExistentCodeSystemSeverity(IValidationSupport.IssueSeverity.WARNING);

        ValidationSupportChain chain = new ValidationSupportChain(
                ValidationSupportChain.CacheConfiguration.defaultValues(),
                npm,
                new DefaultProfileValidationSupport(fhirContext),
                new CommonCodeSystemsTerminologyService(fhirContext),
                new InMemoryTerminologyServerValidationSupport(fhirContext),
                new SnapshotGeneratingValidationSupport(fhirContext),
                unknownCodeSystems);

        FhirInstanceValidator module = new FhirInstanceValidator(chain);
        module.setErrorForUnknownProfiles(true);
        module.setBestPracticeWarningLevel(BestPracticeWarningLevel.Warning);

        FhirValidator validator = fhirContext.newValidator();
        validator.registerValidatorModule(module);
        log.info("Validation chain built in {} ms", System.currentTimeMillis() - start);
        return validator;
    }

    @Bean
    public ValidatorInfo validatorInfo(EpiValidationProperties properties) throws IOException {
        try (InputStream in = new ClassPathResource(properties.igPackage()).getInputStream()) {
            NpmPackage pkg = NpmPackage.fromPackage(in);
            return new ValidatorInfo(FHIR_VERSION, VersionUtil.getVersion(), pkg.name(), pkg.version());
        }
    }

    /**
     * Warm-up validation forces snapshot generation and cache priming (~30-60 s). Spring Boot
     * flips readiness to ACCEPTING_TRAFFIC only after ApplicationRunners complete, so the
     * service is never ready before the validator is.
     */
    @Bean
    public ApplicationRunner validatorWarmup(FhirValidator validator, EpiValidationProperties properties) {
        return args -> {
            ClassPathResource warmup = new ClassPathResource("warmup/warmup-bundle.json");
            if (!warmup.exists()) {
                log.warn("No warmup/warmup-bundle.json; first live validation pays the snapshot cost");
                return;
            }
            String raw = new String(warmup.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            long start = System.currentTimeMillis();
            var result = validator.validateWithResult(raw,
                    new ValidationOptions().addProfile(properties.bundleProfile()));
            log.info("Warm-up validation took {} ms ({} messages)",
                    System.currentTimeMillis() - start, result.getMessages().size());
        };
    }

    private static List<String> packageLocations(EpiValidationProperties properties) {
        // NpmPackageValidationSupport does NOT resolve package dependencies: the IG package and
        // every declared dependency must be listed explicitly in configuration.
        List<String> locations = new ArrayList<>();
        locations.add(properties.igPackage());
        if (properties.dependencyPackages() != null) {
            locations.addAll(properties.dependencyPackages());
        }
        return locations;
    }
}
