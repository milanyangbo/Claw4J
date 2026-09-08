package com.claw4j.orchestrator.dto;

import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Stable Orchestrator-local representation of parsed model output.
 */
public final class StandardModelOutput {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_PARSER_FAILURE = "PARSER_FAILURE";

    private static final String VISIBLE_CONTENT_FIELD = "visibleContent";
    private static final String MODEL_TYPE_FIELD = "modelType";
    private static final String PARSER_STATUS_FIELD = "parserStatus";
    private static final String REASONING_REDACTED_FIELD = "reasoningRedacted";
    private static final String REQUIRED_FIELD_SUFFIX = " is required";

    private final String visibleContent;
    private final ModelType modelType;
    private final String parserStatus;
    private final boolean reasoningRedacted;

    /**
     * Creates a parsed model output DTO.
     *
     * @param visibleContent user-visible content after parser redaction
     * @param modelType model family that produced the output
     * @param parserStatus parser status code
     * @param reasoningRedacted whether reasoning markers were redacted
     */
    @JsonCreator
    public StandardModelOutput(
            @JsonProperty(VISIBLE_CONTENT_FIELD) String visibleContent,
            @JsonProperty(MODEL_TYPE_FIELD) ModelType modelType,
            @JsonProperty(PARSER_STATUS_FIELD) String parserStatus,
            @JsonProperty(REASONING_REDACTED_FIELD) boolean reasoningRedacted
    ) {
        this.visibleContent = defaultIfMissing(visibleContent);
        this.modelType = Objects.requireNonNull(modelType, MODEL_TYPE_FIELD + REQUIRED_FIELD_SUFFIX);
        this.parserStatus = requireText(parserStatus, PARSER_STATUS_FIELD);
        this.reasoningRedacted = reasoningRedacted;
    }

    /**
     * Creates a successful parsed output.
     *
     * @param visibleContent user-visible content
     * @param modelType model family that produced the output
     * @param reasoningRedacted whether reasoning markers were redacted
     * @return successful parsed output
     */
    public static StandardModelOutput success(
            String visibleContent,
            ModelType modelType,
            boolean reasoningRedacted
    ) {
        return new StandardModelOutput(visibleContent, modelType, STATUS_OK, reasoningRedacted);
    }

    /**
     * Creates a stable parser-failure output.
     *
     * @param modelType model family that produced the output
     * @param reasoningRedacted whether reasoning markers were redacted
     * @return parser-failure output
     */
    public static StandardModelOutput parserFailure(ModelType modelType, boolean reasoningRedacted) {
        return new StandardModelOutput("", modelType, STATUS_PARSER_FAILURE, reasoningRedacted);
    }

    /**
     * Returns the user-visible content.
     *
     * @return user-visible content
     */
    public String getVisibleContent() {
        return visibleContent;
    }

    /**
     * Returns the model family.
     *
     * @return model type
     */
    public ModelType getModelType() {
        return modelType;
    }

    /**
     * Returns the parser status code.
     *
     * @return parser status
     */
    public String getParserStatus() {
        return parserStatus;
    }

    /**
     * Returns whether parsing succeeded.
     *
     * @return true when parser status is OK
     */
    public boolean isSuccessful() {
        return STATUS_OK.equals(parserStatus);
    }

    /**
     * Returns whether reasoning markers were removed.
     *
     * @return true when reasoning markers were redacted
     */
    public boolean isReasoningRedacted() {
        return reasoningRedacted;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, fieldName + REQUIRED_FIELD_SUFFIX);
        }
        return value;
    }

    private static String defaultIfMissing(String value) {
        if (value == null) {
            return "";
        }
        return value;
    }
}
