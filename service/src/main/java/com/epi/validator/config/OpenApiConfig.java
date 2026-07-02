package com.epi.validator.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI epiValidatorOpenApi() {
        return new OpenAPI().info(new Info()
                .title("FHIR ePI Validator")
                .description("""
                        Regulated validation gate for FHIR ePI (electronic Product Information) documents.

                        Primary API: POST /api/v1/epi/validate. Production pipeline callers SHOULD pass \
                        epiType explicitly and use validationMode=gate; epiType=auto is for exploratory \
                        validation, diagnostics, demos, and malformed inbound triage.

                        POST /fhir/$validate is a FHIR interop/debug endpoint only — not the regulated \
                        pipeline gate.""")
                .version("v1")
                .license(new License().name("Apache-2.0")));
    }
}
