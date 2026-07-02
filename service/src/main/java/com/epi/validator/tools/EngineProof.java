package com.epi.validator.tools;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationOptions;
import ca.uhn.fhir.validation.ValidationResult;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.UnknownCodeSystemWarningValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.r5.utils.validation.constants.BestPracticeWarningLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Offline proof that the vendored IG packages + HAPI validation chain work end to end,
 * run before any Spring wiring exists. Validates the IG example bundles for both IG
 * versions and prints issue summaries, timings, and heap usage.
 *
 * Usage: mvn -f service/pom.xml compile exec:java [-Dexec.args="<examples-dir>"]
 */
public final class EngineProof {

    private EngineProof() {
    }

    public static void main(String[] args) throws IOException {
        Path examplesDir = Path.of(args.length > 0 ? args[0] : "../examples/bundles");
        FhirContext ctx = FhirContext.forR5();

        System.out.println("=== IG 1.0.0 (published STU1, generic Bundle-uv-epi profile) ===");
        FhirValidator v100 = buildValidator(ctx, List.of(
                "classpath:packages/hl7.fhir.uv.emedicinal-product-info-1.0.0.tgz",
                "classpath:packages/hl7.terminology.r5-5.0.0.tgz",
                "classpath:packages/hl7.fhir.uv.extensions.r5-1.0.0.tgz"));
        try (Stream<Path> files = Files.list(examplesDir.resolve("1.0.0"))) {
            for (Path f : files.sorted().toList()) {
                validateAndReport(v100, f,
                        "http://hl7.org/fhir/uv/emedicinal-product-info/StructureDefinition/Bundle-uv-epi");
            }
        }

        System.out.println();
        System.out.println("=== IG 1.1.0 CI snapshot (type-specific bundle-epi-typeN profiles) ===");
        FhirValidator v110 = buildValidator(ctx, List.of(
                "classpath:packages/hl7.fhir.uv.emedicinal-product-info-1.1.0-cibuild-20260701.tgz",
                "classpath:packages/hl7.terminology.r5-7.2.0.tgz",
                "classpath:packages/hl7.fhir.uv.extensions.r5-5.3.0.tgz"));
        try (Stream<Path> files = Files.list(examplesDir.resolve("1.1.0"))) {
            for (Path f : files.sorted().toList()) {
                String name = f.getFileName().toString();
                String type = name.contains("type1") ? "1" : name.contains("type2") ? "2" : "3";
                validateAndReport(v110, f,
                        "http://hl7.org/fhir/uv/emedicinal-product-info/StructureDefinition/bundle-epi-type" + type);
            }
        }

        Runtime rt = Runtime.getRuntime();
        System.out.printf("%nHeap used: %d MB (max %d MB)%n",
                (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024), rt.maxMemory() / (1024 * 1024));
    }

    private static FhirValidator buildValidator(FhirContext ctx, List<String> packages) throws IOException {
        long start = System.currentTimeMillis();
        NpmPackageValidationSupport npm = new NpmPackageValidationSupport(ctx);
        for (String pkg : packages) {
            npm.loadPackageFromClasspath(pkg);
        }
        UnknownCodeSystemWarningValidationSupport unknownCodeSystems =
                new UnknownCodeSystemWarningValidationSupport(ctx);
        unknownCodeSystems.setNonExistentCodeSystemSeverity(IValidationSupport.IssueSeverity.WARNING);

        ValidationSupportChain chain = new ValidationSupportChain(
                ValidationSupportChain.CacheConfiguration.defaultValues(),
                npm,
                new DefaultProfileValidationSupport(ctx),
                new CommonCodeSystemsTerminologyService(ctx),
                new InMemoryTerminologyServerValidationSupport(ctx),
                new SnapshotGeneratingValidationSupport(ctx),
                unknownCodeSystems);

        FhirInstanceValidator module = new FhirInstanceValidator(chain);
        module.setErrorForUnknownProfiles(true);
        module.setBestPracticeWarningLevel(BestPracticeWarningLevel.Warning);

        FhirValidator validator = ctx.newValidator();
        validator.registerValidatorModule(module);
        System.out.printf("Chain built in %d ms with packages %s%n", System.currentTimeMillis() - start, packages);
        return validator;
    }

    private static void validateAndReport(FhirValidator validator, Path file, String profile) throws IOException {
        String raw = Files.readString(file);
        long start = System.currentTimeMillis();
        ValidationResult result = validator.validateWithResult(raw, new ValidationOptions().addProfile(profile));
        long duration = System.currentTimeMillis() - start;

        Map<ResultSeverityEnum, Integer> counts = new EnumMap<>(ResultSeverityEnum.class);
        for (SingleValidationMessage m : result.getMessages()) {
            counts.merge(m.getSeverity(), 1, Integer::sum);
        }
        System.out.printf("%n%s  [%d ms]  profile=%s%n", file.getFileName(), duration, profile);
        System.out.printf("  fatal=%d error=%d warning=%d info=%d%n",
                counts.getOrDefault(ResultSeverityEnum.FATAL, 0),
                counts.getOrDefault(ResultSeverityEnum.ERROR, 0),
                counts.getOrDefault(ResultSeverityEnum.WARNING, 0),
                counts.getOrDefault(ResultSeverityEnum.INFORMATION, 0));
        result.getMessages().stream()
                .filter(m -> m.getSeverity().ordinal() >= ResultSeverityEnum.ERROR.ordinal())
                .limit(8)
                .forEach(m -> System.out.printf("  [%s] %s @ %s (line %s)%n",
                        m.getSeverity(), truncate(m.getMessage()), m.getLocationString(), m.getLocationLine()));
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 160 ? s.substring(0, 157) + "..." : s;
    }
}
