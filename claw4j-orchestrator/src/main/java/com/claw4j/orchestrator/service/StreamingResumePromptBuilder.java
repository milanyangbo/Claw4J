package com.claw4j.orchestrator.service;

import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Builds fallback resume prompts with explicit data boundaries.
 */
@Component
public class StreamingResumePromptBuilder {

    private static final String USER_QUERY_TAG = "user_query";
    private static final String CACHED_OUTPUT_TAG = "cached_output";
    private static final String RESUME_INSTRUCTION = "Continue the assistant answer strictly after the cached output. "
            + "Do not repeat earlier content, and keep the same language, tone, and format.";
    private static final String CDATA_END = "]]>";
    private static final String ESCAPED_CDATA_END = "]]&gt;";

    /**
     * Builds a prompt for fallback continuation.
     *
     * @param originalQuery original user query
     * @param cachedOutput already emitted assistant output
     * @return resume prompt with user data isolated from recovery instructions
     */
    public String build(String originalQuery, String cachedOutput) {
        return RESUME_INSTRUCTION
                + System.lineSeparator()
                + wrapData(USER_QUERY_TAG, requireText(originalQuery, "originalQuery"))
                + System.lineSeparator()
                + wrapData(CACHED_OUTPUT_TAG, defaultIfMissing(cachedOutput));
    }

    private static String wrapData(String tagName, String value) {
        return "<" + tagName + "><![CDATA[" + escapeCdata(value) + "]]></" + tagName + ">";
    }

    private static String escapeCdata(String value) {
        return value.replace(CDATA_END, ESCAPED_CDATA_END);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, fieldName + " is required");
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
