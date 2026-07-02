package com.epi.validator.engine;

import ca.uhn.fhir.validation.ValidationOptions;
import ca.uhn.fhir.validation.ValidationResult;
import com.epi.validator.config.EpiValidationProperties;
import com.epi.validator.config.EpiValidationProperties.IgConfig;
import com.epi.validator.model.EpiType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Builds every configured IG chain at startup and runs a warm-up validation against each to
 * force snapshot generation and prime caches.
 *
 * <p>Spring Boot flips readiness to ACCEPTING_TRAFFIC only after all ApplicationRunners have
 * completed, so the service is not ready until packages are loaded, snapshots are generated,
 * and the warm-up validations have run — by construction.
 */
@Component
public class ValidatorWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ValidatorWarmupRunner.class);

    private final ValidatorFactory factory;
    private final ValidatorRegistry registry;
    private final EpiValidationProperties properties;

    public ValidatorWarmupRunner(ValidatorFactory factory,
                                 ValidatorRegistry registry,
                                 EpiValidationProperties properties) {
        this.factory = factory;
        this.registry = registry;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        long start = System.currentTimeMillis();
        List<IgConfig> igs = properties.igs();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(igs.size(), 2));
        try {
            List<Future<IgRuntime>> futures = igs.stream()
                    .map(ig -> pool.submit(() -> buildAndWarm(ig)))
                    .toList();
            for (Future<IgRuntime> f : futures) {
                registry.register(f.get());
            }
        } catch (ExecutionException e) {
            throw new IllegalStateException("Validator warm-up failed — refusing to become ready", e.getCause());
        } finally {
            pool.shutdown();
        }
        registry.markReady();
        log.info("All {} validator chains built and warmed in {} ms",
                igs.size(), System.currentTimeMillis() - start);
    }

    private IgRuntime buildAndWarm(IgConfig config) throws IOException {
        IgRuntime runtime = factory.buildFor(config);
        String warmupPath = "warmup/warmup-bundle-" + config.id() + ".json";
        ClassPathResource resource = new ClassPathResource(warmupPath);
        if (!resource.exists()) {
            log.warn("No warm-up bundle at {}; first live validation for IG {} will pay the snapshot cost",
                    warmupPath, config.id());
            return runtime;
        }
        String raw = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        long start = System.currentTimeMillis();
        String profile = config.profileFor(EpiType.TYPE_1).orElse(null);
        ValidationOptions options = new ValidationOptions();
        if (profile != null) {
            options.addProfile(profile);
        }
        ValidationResult result = runtime.validator().validateWithResult(raw, options);
        log.info("Warm-up validation for IG {} took {} ms ({} messages)",
                config.id(), System.currentTimeMillis() - start, result.getMessages().size());
        return runtime;
    }
}
