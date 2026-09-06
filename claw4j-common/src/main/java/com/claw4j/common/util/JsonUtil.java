package com.claw4j.common.util;

import com.claw4j.common.exception.Claw4jException;
import com.claw4j.common.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Objects;

/**
 * Provides shared JSON serialization and deserialization behavior.
 */
public final class JsonUtil {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonUtil() {
    }

    /**
     * Serializes a supported value to JSON.
     *
     * @param value value to serialize
     * @return non-empty JSON string
     */
    public static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new Claw4jException(ErrorCode.JSON_SERIALIZATION_ERROR, "JSON serialization failed", exception);
        }
    }

    /**
     * Deserializes JSON into the requested target type.
     *
     * @param json source JSON
     * @param targetType target class
     * @param <T> target value type
     * @return deserialized value
     */
    public static <T> T fromJson(String json, Class<T> targetType) {
        Objects.requireNonNull(targetType, "targetType must not be null");
        try {
            return OBJECT_MAPPER.readValue(json, targetType);
        } catch (IOException exception) {
            throw new Claw4jException(ErrorCode.JSON_DESERIALIZATION_ERROR, "JSON deserialization failed", exception);
        }
    }
}
