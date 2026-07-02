package com.epi.validator;

import com.epi.validator.config.EpiValidationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(EpiValidationProperties.class)
public class EpiValidatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(EpiValidatorApplication.class, args);
    }
}
