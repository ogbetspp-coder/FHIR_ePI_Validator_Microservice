package com.epi.validator.config;

import com.epi.validator.model.ValidationMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** Binds the real application.yaml (no app boot) and asserts the configuration surface. */
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
            assertThat(props.defaultIg()).isEqualTo("1.0.0");
            assertThat(props.defaultValidationMode()).isEqualTo(ValidationMode.EXPLORATORY);
            assertThat(props.maxConcurrentValidations()).isEqualTo(8);
            assertThat(props.concurrentBundleValidation()).isFalse();
            assertThat(props.profileOverride().allowedIn(ValidationMode.EXPLORATORY)).isTrue();
            assertThat(props.profileOverride().allowedIn(ValidationMode.GATE)).isFalse();
            assertThat(props.warningPolicy().mode())
                    .isEqualTo(EpiValidationProperties.WarningPolicyMode.PASS_WITH_WARNINGS);
            assertThat(props.policyPacks()).containsExactly("epi-gate-core");
            assertThat(props.igs()).hasSize(2);
            assertThat(props.igConfigOrThrow(null).id()).isEqualTo("1.0.0");
            assertThat(props.igConfigOrThrow("1.1.0").typeProfiles())
                    .containsKeys("1", "2", "3");
            assertThat(props.igConfigOrThrow("1.1.0").dependencyPackages()).hasSize(2);
        });
    }

    @Test
    void environmentStyleOverridesApply() {
        runner.withPropertyValues(
                        "epi.validation.default-validation-mode=gate",
                        "epi.validation.warning-policy.mode=fail-unless-allowlisted",
                        "epi.validation.max-concurrent-validations=2")
                .run(ctx -> {
                    EpiValidationProperties props = ctx.getBean(EpiValidationProperties.class);
                    assertThat(props.defaultValidationMode()).isEqualTo(ValidationMode.GATE);
                    assertThat(props.warningPolicy().mode())
                            .isEqualTo(EpiValidationProperties.WarningPolicyMode.FAIL_UNLESS_ALLOWLISTED);
                    assertThat(props.maxConcurrentValidations()).isEqualTo(2);
                });
    }
}
