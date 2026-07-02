package com.epi.validator.config;

import ca.uhn.fhir.context.FhirContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FhirConfig {

    /**
     * One R5 context per JVM — FhirContext construction is expensive and the instance is
     * thread-safe. Parsers are created per request; they are cheap.
     */
    @Bean
    public FhirContext fhirContextR5() {
        return FhirContext.forR5();
    }
}
