package com.epi.validator.engine;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import com.epi.validator.config.EpiValidationProperties;
import com.epi.validator.config.EpiValidationProperties.IgConfig;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.RemoteTerminologyServiceValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.UnknownCodeSystemWarningValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.r5.utils.validation.constants.BestPracticeWarningLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Builds one fully isolated validation chain per IG version.
 *
 * <p><b>Invariant: never merge IG versions into one chain.</b> ePI 1.0.0 and the 1.1.0 CI
 * snapshot define StructureDefinitions under the same canonical base
 * ({@code http://hl7.org/fhir/uv/emedicinal-product-info/...}); a merged chain would resolve
 * profiles across versions non-deterministically.
 */
@Component
public class ValidatorFactory {

    private static final Logger log = LoggerFactory.getLogger(ValidatorFactory.class);

    private final FhirContext fhirContext;
    private final PackageSetLoader packageSetLoader;
    private final EpiValidationProperties properties;

    public ValidatorFactory(FhirContext fhirContext,
                            PackageSetLoader packageSetLoader,
                            EpiValidationProperties properties) {
        this.fhirContext = fhirContext;
        this.packageSetLoader = packageSetLoader;
        this.properties = properties;
    }

    public IgRuntime buildFor(IgConfig config) {
        long start = System.currentTimeMillis();
        NpmPackageValidationSupport npm = packageSetLoader.loadValidationSupport(config);

        UnknownCodeSystemWarningValidationSupport unknownCodeSystems =
                new UnknownCodeSystemWarningValidationSupport(fhirContext);
        // ePI content references code systems with no distributable offline representation
        // (EDQM Standard Terms, MedDRA, WHO ATC). Without this downgrade, offline mode would
        // hard-fail every real-world ePI on codes nobody can validate locally. A remote
        // terminology server restores strictness where deployed.
        unknownCodeSystems.setNonExistentCodeSystemSeverity(
                "error".equalsIgnoreCase(properties.unknownCodeSystemSeverity())
                        ? IValidationSupport.IssueSeverity.ERROR
                        : IValidationSupport.IssueSeverity.WARNING);

        List<IValidationSupport> modules = new ArrayList<>();
        modules.add(npm);
        modules.add(new DefaultProfileValidationSupport(fhirContext));
        modules.add(new CommonCodeSystemsTerminologyService(fhirContext));
        if (properties.remoteTerminology() != null && properties.remoteTerminology().enabled()) {
            RemoteTerminologyServiceValidationSupport remote =
                    new RemoteTerminologyServiceValidationSupport(fhirContext, properties.remoteTerminology().url());
            modules.add(remote);
            log.info("Remote terminology enabled: {}", properties.remoteTerminology().url());
        }
        modules.add(new InMemoryTerminologyServerValidationSupport(fhirContext));
        modules.add(new SnapshotGeneratingValidationSupport(fhirContext));
        modules.add(unknownCodeSystems);

        // ValidationSupportChain's built-in cache replaces the deprecated CachingValidationSupport.
        ValidationSupportChain chain = new ValidationSupportChain(
                ValidationSupportChain.CacheConfiguration.defaultValues(),
                modules.toArray(IValidationSupport[]::new));

        FhirInstanceValidator module = new FhirInstanceValidator(chain);
        module.setErrorForUnknownProfiles(true);
        module.setBestPracticeWarningLevel(BestPracticeWarningLevel.Warning);

        FhirValidator validator = fhirContext.newValidator();
        validator.registerValidatorModule(module);
        if (properties.concurrentBundleValidation()) {
            // Off by default in the first production cut: bundle-level concurrency stacks with the
            // request bulkhead and platform concurrency; enable only with sized capacity planning.
            ExecutorService executor = Executors.newFixedThreadPool(
                    Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
            validator.setConcurrentBundleValidation(true);
            validator.setExecutorService(executor);
            log.info("Concurrent bundle validation enabled for IG {}", config.id());
        }

        List<IgPackageMetadata> metadata = packageSetLoader.loadMetadata(config);
        log.info("Validator chain for IG {} built in {} ms ({} packages)",
                config.id(), System.currentTimeMillis() - start, metadata.size());
        return new IgRuntime(config, validator, metadata);
    }
}
