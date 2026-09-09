package com.claw4j.orchestrator.service;

import com.claw4j.common.dto.StreamingModelRequest;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.orchestrator.client.ModelProviderClient;
import com.claw4j.orchestrator.dto.ModelType;
import com.claw4j.orchestrator.dto.StreamingRequestContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Streams provider-backed model output with Resilience4j primary-call governance.
 */
@Service
@Primary
public class ResilientModelStreamClient implements ModelStreamClient {

    private static final String PRIMARY_CIRCUIT_NAME = "deepseek-primary-model";
    private static final String PRIMARY_TIMEOUT_MESSAGE = "primary model timed out";
    private static final String PRIMARY_FAILURE_MESSAGE = "primary model provider failed";
    private static final String PRIMARY_CIRCUIT_OPEN_MESSAGE = "primary model circuit is open";
    private static final String PROVIDER_CONFIG_MESSAGE = "real model provider client is not configured";
    private static final String EXECUTOR_THREAD_PREFIX = "claw4j-model-primary-";

    private final ModelProviderClient providerClient;
    private final CircuitBreaker primaryCircuitBreaker;
    private final TimeLimiter primaryTimeLimiter;
    private final ExecutorService primaryExecutorService;

    /**
     * Creates the resilient model stream client for Spring injection.
     *
     * @param providerClientProvider optional provider-backed model client
     * @param circuitBreakerRegistry official Resilience4j circuit-breaker registry
     * @param timeLimiterRegistry official Resilience4j time-limiter registry
     */
    @Autowired
    public ResilientModelStreamClient(
            ObjectProvider<ModelProviderClient> providerClientProvider,
            CircuitBreakerRegistry circuitBreakerRegistry,
            TimeLimiterRegistry timeLimiterRegistry
    ) {
        this(
                providerClientProvider.getIfAvailable(),
                circuitBreakerRegistry.circuitBreaker(PRIMARY_CIRCUIT_NAME),
                timeLimiterRegistry.timeLimiter(PRIMARY_CIRCUIT_NAME)
        );
    }

    /**
     * Creates the resilient model stream client with an explicit provider client.
     *
     * @param providerClient provider-backed model client
     * @param primaryCircuitBreaker circuit breaker for the primary model
     * @param primaryTimeLimiter time limiter for the primary model
     */
    public ResilientModelStreamClient(
            ModelProviderClient providerClient,
            CircuitBreaker primaryCircuitBreaker,
            TimeLimiter primaryTimeLimiter
    ) {
        this.providerClient = providerClient;
        this.primaryCircuitBreaker = Objects.requireNonNull(
                primaryCircuitBreaker,
                "primaryCircuitBreaker must not be null"
        );
        this.primaryTimeLimiter = Objects.requireNonNull(primaryTimeLimiter, "primaryTimeLimiter must not be null");
        this.primaryExecutorService = Executors.newCachedThreadPool(new ModelThreadFactory());
    }

    /**
     * Streams model output using provider-backed runtime behavior.
     *
     * @param modelType model family to invoke
     * @param prompt adapted prompt for model invocation
     * @param request business request
     * @param context Header-derived request context
     * @param tokenConsumer consumer that receives raw model tokens
     */
    @Override
    public void stream(
            ModelType modelType,
            String prompt,
            StreamingModelRequest request,
            StreamingRequestContext context,
            TokenConsumer tokenConsumer
    ) {
        Objects.requireNonNull(modelType, "modelType must not be null");
        requireText(prompt, "prompt");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(tokenConsumer, "tokenConsumer must not be null");

        if (ModelType.DEEPSEEK == modelType) {
            streamPrimary(prompt, request, context, tokenConsumer);
            return;
        }
        requireProviderClient().stream(modelType, prompt, request, context, tokenConsumer);
    }

    /**
     * Stops background primary-call workers during application shutdown.
     */
    @PreDestroy
    public void shutdown() {
        primaryExecutorService.shutdownNow();
    }

    private void streamPrimary(
            String prompt,
            StreamingModelRequest request,
            StreamingRequestContext context,
            TokenConsumer tokenConsumer
    ) {
        if (!primaryCircuitBreaker.tryAcquirePermission()) {
            throw new ModelStreamException(PRIMARY_CIRCUIT_OPEN_MESSAGE, true, STATUS_PRIMARY_CIRCUIT_OPEN);
        }

        AtomicBoolean acceptingTokens = new AtomicBoolean(true);
        AtomicBoolean emittedAnyToken = new AtomicBoolean(false);
        long startedAt = System.nanoTime();
        Future<Boolean> future = primaryExecutorService.submit(() -> {
            requireProviderClient().stream(
                    ModelType.DEEPSEEK,
                    prompt,
                    request,
                    context,
                    token -> forwardPrimaryToken(token, tokenConsumer, acceptingTokens, emittedAnyToken)
            );
            return Boolean.TRUE;
        });

        try {
            primaryTimeLimiter.executeFutureSupplier(() -> future);
            primaryCircuitBreaker.onSuccess(elapsedNanos(startedAt), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            acceptingTokens.set(false);
            future.cancel(true);
            primaryCircuitBreaker.onError(elapsedNanos(startedAt), TimeUnit.NANOSECONDS, exception);
            throw new ModelStreamException(
                    PRIMARY_TIMEOUT_MESSAGE,
                    !emittedAnyToken.get(),
                    statusForTimeout(emittedAnyToken),
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            acceptingTokens.set(false);
            future.cancel(true);
            primaryCircuitBreaker.onError(elapsedNanos(startedAt), TimeUnit.NANOSECONDS, exception);
            throw new ModelStreamException(
                    PRIMARY_FAILURE_MESSAGE,
                    !emittedAnyToken.get(),
                    statusForProviderFailure(emittedAnyToken),
                    exception
            );
        } catch (ExecutionException exception) {
            acceptingTokens.set(false);
            Throwable cause = unwrapExecutionException(exception);
            if (cause instanceof BusinessException businessException
                    && ErrorCode.MODEL_PROVIDER_CONFIGURATION_INVALID == businessException.getErrorCode()) {
                throw businessException;
            }
            primaryCircuitBreaker.onError(elapsedNanos(startedAt), TimeUnit.NANOSECONDS, cause);
            throw new ModelStreamException(
                    PRIMARY_FAILURE_MESSAGE,
                    !emittedAnyToken.get(),
                    statusForProviderFailure(emittedAnyToken),
                    cause
            );
        } catch (RuntimeException exception) {
            acceptingTokens.set(false);
            primaryCircuitBreaker.onError(elapsedNanos(startedAt), TimeUnit.NANOSECONDS, exception);
            throw new ModelStreamException(
                    PRIMARY_FAILURE_MESSAGE,
                    !emittedAnyToken.get(),
                    statusForProviderFailure(emittedAnyToken),
                    exception
            );
        } catch (Exception exception) {
            acceptingTokens.set(false);
            primaryCircuitBreaker.onError(elapsedNanos(startedAt), TimeUnit.NANOSECONDS, exception);
            throw new ModelStreamException(
                    PRIMARY_FAILURE_MESSAGE,
                    !emittedAnyToken.get(),
                    statusForProviderFailure(emittedAnyToken),
                    exception
            );
        }
    }

    private void forwardPrimaryToken(
            String token,
            TokenConsumer tokenConsumer,
            AtomicBoolean acceptingTokens,
            AtomicBoolean emittedAnyToken
    ) {
        if (!acceptingTokens.get()) {
            return;
        }
        tokenConsumer.accept(token);
        emittedAnyToken.set(true);
    }

    private ModelProviderClient requireProviderClient() {
        if (providerClient == null) {
            throw new BusinessException(
                    ErrorCode.MODEL_PROVIDER_CONFIGURATION_INVALID,
                    PROVIDER_CONFIG_MESSAGE
            );
        }
        return providerClient;
    }

    private static String statusForTimeout(AtomicBoolean emittedAnyToken) {
        if (emittedAnyToken.get()) {
            return STATUS_PRIMARY_INTERRUPTED;
        }
        return STATUS_PRIMARY_TTFB_TIMEOUT;
    }

    private static String statusForProviderFailure(AtomicBoolean emittedAnyToken) {
        if (emittedAnyToken.get()) {
            return STATUS_PRIMARY_INTERRUPTED;
        }
        return STATUS_PRIMARY_PROVIDER_FAILURE;
    }

    private static Throwable unwrapExecutionException(ExecutionException exception) {
        Throwable cause = exception.getCause();
        if (cause == null) {
            return exception;
        }
        return cause;
    }

    private static long elapsedNanos(long startedAt) {
        return System.nanoTime() - startedAt;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, fieldName + " is required");
        }
        return value;
    }

    private static final class ModelThreadFactory implements ThreadFactory {

        private final AtomicInteger threadIndex = new AtomicInteger();

        /**
         * Creates a daemon worker thread for primary model calls.
         *
         * @param runnable worker task
         * @return daemon worker thread
         */
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, EXECUTOR_THREAD_PREFIX + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
