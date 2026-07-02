package com.epi.validator.service;

import com.epi.validator.config.EpiValidationProperties;
import com.epi.validator.model.ValidationMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidationExecutorTest {

    private static EpiValidationProperties props(int maxConcurrent, int timeoutSeconds) {
        return new EpiValidationProperties(
                "1.0.0", ValidationMode.EXPLORATORY, 50, maxConcurrent, timeoutSeconds, false,
                "warning", true,
                new EpiValidationProperties.ProfileOverride(true, List.of(ValidationMode.EXPLORATORY)),
                new EpiValidationProperties.RemoteTerminology(false, ""),
                new EpiValidationProperties.WarningPolicyProperties(
                        EpiValidationProperties.WarningPolicyMode.PASS_WITH_WARNINGS, List.of()),
                List.of(), List.of());
    }

    @Test
    void executesAndReturnsResult() {
        ValidationExecutor executor = new ValidationExecutor(props(2, 5));
        assertThat(executor.execute(() -> 41 + 1)).isEqualTo(42);
    }

    @Test
    void saturatedBulkheadRejectsImmediately() throws Exception {
        ValidationExecutor executor = new ValidationExecutor(props(1, 5));
        CountDownLatch occupy = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Object> slow = CompletableFuture.supplyAsync(() -> executor.execute(() -> {
            started.countDown();
            occupy.await();
            return "done";
        }));
        started.await();
        try {
            assertThatThrownBy(() -> executor.execute(() -> "second"))
                    .isInstanceOf(ValidationExecutor.BulkheadFullException.class);
        } finally {
            occupy.countDown();
        }
        assertThat(slow.get()).isEqualTo("done");
    }

    @Test
    void slowValidationTimesOut() {
        ValidationExecutor executor = new ValidationExecutor(props(1, 1));
        assertThatThrownBy(() -> executor.execute(() -> {
            Thread.sleep(5_000);
            return "never";
        })).isInstanceOf(ValidationExecutor.ValidationTimeoutException.class);
    }
}
