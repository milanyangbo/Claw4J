package com.claw4j.common.util;

import com.claw4j.common.constant.CommonConstants;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Generates traceable identifiers for requests and idempotency keys.
 */
public final class IdUtil {

    private static final int MAX_PREFIX_LENGTH = 32;
    private static final Pattern PREFIX_PATTERN = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_-]{0," + (MAX_PREFIX_LENGTH - 1) + "}"
    );

    private IdUtil() {
    }

    /**
     * Generates an identifier using the default common prefix.
     *
     * @return non-empty identifier
     */
    public static String generate() {
        return generate(CommonConstants.DEFAULT_ID_PREFIX);
    }

    /**
     * Generates an identifier with a validated contextual prefix.
     *
     * @param prefix contextual identifier prefix
     * @return non-empty prefixed identifier
     */
    public static String generate(String prefix) {
        String validPrefix = validatePrefix(prefix);
        return validPrefix + CommonConstants.ID_SEPARATOR + UUID.randomUUID();
    }

    private static String validatePrefix(String prefix) {
        if (prefix == null || prefix.isBlank() || !PREFIX_PATTERN.matcher(prefix).matches()) {
            throw new BusinessException(ErrorCode.INVALID_ID_PREFIX);
        }
        return prefix;
    }
}
