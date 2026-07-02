package com.epi.validator.health;

import com.epi.validator.engine.IgRuntime;
import com.epi.validator.engine.ValidatorRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Readiness contributor: DOWN until every configured IG has a built, warmed validator chain.
 * Included in the readiness group via management.health.group.readiness.include.
 */
@Component("validators")
public class ValidatorsHealthIndicator implements HealthIndicator {

    private final ValidatorRegistry registry;

    public ValidatorsHealthIndicator(ValidatorRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        Health.Builder builder = registry.isReady() ? Health.up() : Health.down();
        builder.withDetail("igs", registry.all().stream()
                .collect(Collectors.toMap(r -> r.config().id(), r -> "ready")));
        return builder.build();
    }
}
