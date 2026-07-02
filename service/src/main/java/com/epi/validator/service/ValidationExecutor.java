package com.epi.validator.service;

import com.epi.validator.config.EpiValidationProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The single concurrency knob of the service: a bounded validation pool. Excess requests are
 * rejected immediately (429 upstream) instead of queueing; slow validations are cancelled at
 * the request timeout (503 upstream). Keep this aligned with the platform's per-instance
 * concurrency (e.g. Cloud Run --concurrency) — never stack unbounded concurrency layers.
 */
@Component
public class ValidationExecutor {

    private final ThreadPoolExecutor pool;
    private final int timeoutSeconds;

    public ValidationExecutor(EpiValidationProperties properties) {
        int size = Math.max(1, properties.maxConcurrentValidations());
        this.timeoutSeconds = properties.requestTimeoutSeconds();
        this.pool = new ThreadPoolExecutor(size, size, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "epi-validation");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.pool.allowCoreThreadTimeOut(true);
    }

    public <T> T execute(Callable<T> task) {
        Future<T> future;
        try {
            future = pool.submit(task);
        } catch (RejectedExecutionException e) {
            throw new BulkheadFullException();
        }
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ValidationTimeoutException(timeoutSeconds);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Validation interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Validation failed", e.getCause());
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    public static class BulkheadFullException extends RuntimeException {
        public BulkheadFullException() {
            super("Validation capacity exhausted; retry shortly");
        }
    }

    public static class ValidationTimeoutException extends RuntimeException {
        public ValidationTimeoutException(int seconds) {
            super("Validation exceeded the configured request timeout of " + seconds + "s");
        }
    }
}
