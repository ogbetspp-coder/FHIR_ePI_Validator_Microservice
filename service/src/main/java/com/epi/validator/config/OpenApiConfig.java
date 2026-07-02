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
                        A lean validation service for checking AI-generated FHIR ePI Bundles against \
                        FHIR R5 and the pinned HL7 ePI 1.0.0 STU1 Implementation Guide using the \
                        official HAPI validator. Gate on the envelope's `verdict`, never on HTTP status.""")
                .version("v1")
                .license(new License().name("Apache-2.0")));
    }
}
