package com.epi.validator.engine;

import com.epi.validator.config.EpiValidationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds the built validator runtimes, one per configured IG version. Populated by
 * {@link ValidatorWarmupRunner} at startup; readiness stays DOWN until every configured IG
 * has a warmed runtime.
 */
@Component
public class ValidatorRegistry {

    private final Map<String, IgRuntime> runtimes = new ConcurrentHashMap<>();
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final EpiValidationProperties properties;

    public ValidatorRegistry(EpiValidationProperties properties) {
        this.properties = properties;
    }

    void register(IgRuntime runtime) {
        runtimes.put(runtime.config().id(), runtime);
    }

    void markReady() {
        ready.set(true);
    }

    public boolean isReady() {
        return ready.get();
    }

    public boolean hasRuntime(String igId) {
        return runtimes.containsKey(igId);
    }

    public IgRuntime runtimeFor(String igId) {
        String effective = igId == null || igId.isBlank() ? properties.defaultIg() : igId;
        IgRuntime runtime = runtimes.get(effective);
        if (runtime == null) {
            throw new IllegalStateException("No validator runtime for IG '" + effective + "'"
                    + (ready.get() ? "" : " (validators still warming up)"));
        }
        return runtime;
    }

    public List<IgRuntime> all() {
        return List.copyOf(runtimes.values());
    }

    public Map<String, IgRuntime> byId() {
        return Collections.unmodifiableMap(runtimes);
    }
}
