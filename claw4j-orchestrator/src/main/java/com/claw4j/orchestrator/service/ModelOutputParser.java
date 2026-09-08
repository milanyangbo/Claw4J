package com.claw4j.orchestrator.service;

import com.claw4j.orchestrator.config.OrchestratorStreamingModelProperties;
import com.claw4j.orchestrator.dto.ModelType;
import com.claw4j.orchestrator.dto.StandardModelOutput;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Normalizes heterogeneous model output and removes provider reasoning markers.
 */
@Component
public class ModelOutputParser {

    private static final Pattern DEEPSEEK_THINK_PATTERN = Pattern.compile(
            "(?is)<think>.*?</think>|<thinking>.*?</thinking>"
    );
    private static final Pattern QWQ_THOUGHT_PATTERN = Pattern.compile(
            "(?is)<\\|begin_of_thought\\|>.*?<\\|end_of_thought\\|>|<think>.*?</think>"
    );
    private static final Pattern GENERIC_REASONING_PATTERN = Pattern.compile(
            "(?is)<reasoning>.*?</reasoning>|<think>.*?</think>"
    );
    private static final String EMPTY_TEXT = "";

    private final OrchestratorStreamingModelProperties properties;

    /**
     * Creates the model output parser.
     *
     * @param properties streaming model properties
     */
    public ModelOutputParser(OrchestratorStreamingModelProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * Parses a raw model output into the standard output contract.
     *
     * @param rawOutput raw model output
     * @param modelType model family that produced the output
     * @return standard parsed output
     */
    public StandardModelOutput parse(String rawOutput, ModelType modelType) {
        Objects.requireNonNull(modelType, "modelType must not be null");
        String rawText = defaultIfMissing(rawOutput);
        String visibleContent = redactReasoning(rawText, modelType);
        boolean reasoningRedacted = !rawText.equals(visibleContent);
        if (visibleContent.isBlank() && properties.getParser().isRejectMalformedOutput()) {
            return StandardModelOutput.parserFailure(modelType, reasoningRedacted);
        }
        return StandardModelOutput.success(visibleContent, modelType, reasoningRedacted);
    }

    /**
     * Filters one streaming token before SSE emission.
     *
     * @param rawToken raw model token
     * @param modelType model family that produced the token
     * @return parsed output when token contains visible content
     */
    public Optional<StandardModelOutput> filterStreamingToken(String rawToken, ModelType modelType) {
        Objects.requireNonNull(modelType, "modelType must not be null");
        String rawText = defaultIfMissing(rawToken);
        String visibleContent = redactReasoning(rawText, modelType);
        if (visibleContent.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(StandardModelOutput.success(visibleContent, modelType, !rawText.equals(visibleContent)));
    }

    private static String redactReasoning(String rawOutput, ModelType modelType) {
        if (ModelType.DEEPSEEK == modelType) {
            return DEEPSEEK_THINK_PATTERN.matcher(rawOutput).replaceAll(EMPTY_TEXT);
        }
        if (ModelType.QWQ == modelType) {
            return QWQ_THOUGHT_PATTERN.matcher(rawOutput).replaceAll(EMPTY_TEXT);
        }
        return GENERIC_REASONING_PATTERN.matcher(rawOutput).replaceAll(EMPTY_TEXT);
    }

    private static String defaultIfMissing(String value) {
        if (value == null) {
            return EMPTY_TEXT;
        }
        return value;
    }
}
