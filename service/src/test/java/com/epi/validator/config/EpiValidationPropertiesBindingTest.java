package com.epi.validator.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** Binds the real application.yaml (no app boot) and asserts the slim configuration surface. */
class EpiValidationPropertiesBindingTest {

    @Configuration
    @EnableConfigurationProperties(EpiValidationProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsApplicationYaml() {
        runner.run(ctx -> {
            EpiValidationProperties props = ctx.getBean(EpiValidationProperties.class);
            assertThat(props.maxBodyMb()).isEqualTo(8);
            assertThat(props.maxBundleEntries()).isEqualTo(1000);
            assertThat(props.igPackage())
                    .isEqualTo("packages/hl7.fhir.uv.emedicinal-product-info-1.0.0.tgz");
            assertThat(props.dependencyPackages()).containsExactly(
                    "packages/hl7.terminology.r5-5.0.0.tgz",
                    "packages/hl7.fhir.uv.extensions.r5-1.0.0.tgz");
            assertThat(props.bundleProfile())
                    .isEqualTo("http://hl7.org/fhir/uv/emedicinal-product-info/StructureDefinition/Bundle-uv-epi");
        });
    }

    @Test
    void environmentStyleOverridesApply() {
        runner.withPropertyValues("epi.validation.max-body-mb=5")
                .run(ctx -> assertThat(ctx.getBean(EpiValidationProperties.class).maxBodyMb())
                        .isEqualTo(5));
    }

    @Test
    void nonPositiveMaxBodyAbortsStartup() {
        runner.withPropertyValues("epi.validation.max-body-mb=0")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void nonPositiveMaxBundleEntriesAbortsStartup() {
        runner.withPropertyValues("epi.validation.max-bundle-entries=0")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
