package com.claw4j.orchestrator.service;

import com.claw4j.orchestrator.config.OrchestratorStreamingModelProperties;
import com.claw4j.common.dto.StreamingModelRequest;
import com.claw4j.orchestrator.dto.ModelType;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Deterministic local model stream client used for repeatable proof-path tests.
 */
@Service
public class DeterministicModelStreamClient implements ModelStreamClient {

    private static final String PRIMARY_TTFB_TIMEOUT_MESSAGE = "Primary model TTFB timeout";
    private static final String PRIMARY_INTERRUPTED_MESSAGE = "Primary model stream interrupted";
    private static final String CACHED_OUTPUT_OPEN_TAG = "<cached_output>";
    private static final String CACHED_OUTPUT_CLOSE_TAG = "</cached_output>";
    private static final String CDATA_OPEN = "<![CDATA[";
    private static final String CDATA_CLOSE = "]]>";
    private static final String ESCAPED_CDATA_CLOSE = "]]&gt;";
    private static final String SPACING = " ";
    private static final String DEEPSEEK_REASONING_PREFIX = "<think>local reasoning</think>";
    private static final String QWQ_REASONING_PREFIX = "<|begin_of_thought|>local reasoning<|end_of_thought|>";

    private final OrchestratorStreamingModelProperties properties;

    /**
     * Creates the deterministic model stream client.
     *
     * @param properties streaming model properties
     */
    public DeterministicModelStreamClient(OrchestratorStreamingModelProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * Streams deterministic tokens and optional simulated failures.
     *
     * @param modelType model family to invoke
     * @param prompt adapted prompt for model invocation
     * @param request business request controls for the proof path
     * @param tokenConsumer consumer that receives raw model tokens
     */
    @Override
    public void stream(
            ModelType modelType,
            String prompt,
            StreamingModelRequest request,
            TokenConsumer tokenConsumer
    ) {
        Objects.requireNonNull(modelType, "modelType must not be null");
        requireText(prompt, "prompt");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(tokenConsumer, "tokenConsumer must not be null");

        if (request.isSimulateMalformedOutput()) {
            tokenConsumer.accept(properties.getProofClient().getMalformedOutput());
            return;
        }
        if (isPrimary(modelType)) {
            streamPrimary(request, tokenConsumer);
            return;
        }
        streamFallback(prompt, tokenConsumer);
    }

    private void streamPrimary(StreamingModelRequest request, TokenConsumer tokenConsumer) {
        if (request.isSimulatePrimaryTtfbTimeout()) {
            throw new ModelStreamException(PRIMARY_TTFB_TIMEOUT_MESSAGE, true);
        }
        if (request.isSimulatePrimaryFailure()) {
            tokenConsumer.accept(DEEPSEEK_REASONING_PREFIX + properties.getProofClient().getPrimaryFailurePrefix());
            throw new ModelStreamException(PRIMARY_INTERRUPTED_MESSAGE, false);
        }
        tokenConsumer.accept(DEEPSEEK_REASONING_PREFIX + properties.getProofClient().getPrimarySuccessContent());
    }

    private void streamFallback(String prompt, TokenConsumer tokenConsumer) {
        String cachedOutput = extractCachedOutput(prompt);
        String continuation = properties.getProofClient().getFallbackContinuation();
        if (cachedOutput.isBlank()) {
            tokenConsumer.accept(QWQ_REASONING_PREFIX + continuation);
            return;
        }
        tokenConsumer.accept(QWQ_REASONING_PREFIX + cachedOutput + SPACING + continuation);
    }

    private boolean isPrimary(ModelType modelType) {
        return properties.getProofClient().getPrimaryModelType() == modelType;
    }

    private static String extractCachedOutput(String prompt) {
        int start = prompt.indexOf(CACHED_OUTPUT_OPEN_TAG);
        int end = prompt.indexOf(CACHED_OUTPUT_CLOSE_TAG);
        if (start < 0 || end <= start) {
            return "";
        }
        int contentStart = start + CACHED_OUTPUT_OPEN_TAG.length();
        String content = prompt.substring(contentStart, end).trim();
        if (content.startsWith(CDATA_OPEN) && content.endsWith(CDATA_CLOSE)) {
            int cdataContentStart = CDATA_OPEN.length();
            int cdataContentEnd = content.length() - CDATA_CLOSE.length();
            return content.substring(cdataContentStart, cdataContentEnd).replace(ESCAPED_CDATA_CLOSE, CDATA_CLOSE).trim();
        }
        return content;
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
