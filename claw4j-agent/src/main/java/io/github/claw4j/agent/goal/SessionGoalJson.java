package io.github.claw4j.agent.goal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code entry_json.goal} 与 {@link SessionGoal} 之间的编解码（ADR-0029）。
 *
 * <p>目标在库里占 {@code entry_json} 的一个键，不再摊成十七个列：加一个字段不用改表，代价是
 * 每次读写要整份编解码，而"只改状态"这种增量更新也得先读出来。
 *
 * <p>键名用 camelCase，与它的 {@code SessionGoalSchema} 一致（{@code sessionId}、{@code tokenStart}
 * 这些也一样）。时间存毫秒整数，理由同上——它的 entry 全用毫秒，而 ISO 字符串在多一层转换之后
 * 只会多一处可以写错的地方。
 *
 * <p>状态存 {@link GoalStatus#column()}（小写下划线），不是枚举名：库里出现 {@code ACTIVE} 时，
 * 用它自己的工具去读会认不出。
 *
 * <p>缺字段按默认值读，不抛：目标是"尽力而为"的一份状态，少一个 {@code note} 不该让整轮没有目标。
 * 只有 {@code objective} 与 {@code id} 缺失才判成坏行——那两个缺了就不是一个目标。
 */
final class SessionGoalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SCHEMA_VERSION = "schemaVersion";
    private static final String ID = "id";
    private static final String OBJECTIVE = "objective";
    private static final String STATUS = "status";
    private static final String CREATED_AT = "createdAt";
    private static final String UPDATED_AT = "updatedAt";
    private static final String TOKEN_START = "tokenStart";
    private static final String TOKEN_START_FRESH = "tokenStartFresh";
    private static final String TOKENS_USED = "tokensUsed";
    private static final String TOKEN_BUDGET = "tokenBudget";
    private static final String CONTINUATION_TURNS = "continuationTurns";
    private static final String NOTE = "note";
    private static final String PAUSED_AT = "pausedAt";
    private static final String BLOCKED_AT = "blockedAt";
    private static final String COMPLETED_AT = "completedAt";
    private static final String USAGE_LIMITED_AT = "usageLimitedAt";
    private static final String BUDGET_LIMITED_AT = "budgetLimitedAt";

    private SessionGoalJson() {
    }

    /**
     * 编成 JSON。
     *
     * @param goal 目标，不能为 null
     * @return 可以直接传给 {@code ?::jsonb} 的字符串
     */
    static String toJson(SessionGoal goal) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put(SCHEMA_VERSION, goal.schemaVersion());
        json.put(ID, goal.id().toString());
        json.put(OBJECTIVE, goal.objective());
        json.put(STATUS, goal.status().column());
        json.put(CREATED_AT, millis(goal.createdAt()));
        json.put(UPDATED_AT, millis(goal.updatedAt()));
        json.put(TOKEN_START, goal.tokenStart());
        json.put(TOKEN_START_FRESH, goal.tokenStartFresh());
        json.put(TOKENS_USED, goal.tokensUsed());
        json.put(TOKEN_BUDGET, goal.tokenBudget());
        json.put(CONTINUATION_TURNS, goal.continuationTurns());
        json.put(NOTE, goal.note());
        json.put(PAUSED_AT, millis(goal.pausedAt()));
        json.put(BLOCKED_AT, millis(goal.blockedAt()));
        json.put(COMPLETED_AT, millis(goal.completedAt()));
        json.put(USAGE_LIMITED_AT, millis(goal.usageLimitedAt()));
        json.put(BUDGET_LIMITED_AT, millis(goal.budgetLimitedAt()));
        try {
            return MAPPER.writeValueAsString(json);
        } catch (JsonProcessingException impossible) {
            // 全是字符串与数字，序列化不会失败；真失败说明 Jackson 的配置被人改坏了。
            throw new IllegalStateException("目标编码失败", impossible);
        }
    }

    /**
     * 解回目标。
     *
     * @param sessionKey 这一行所属的节点键，JSON 里不重复存它
     * @param json       {@code entry_json -> 'goal'} 的文本
     * @return 目标；{@code null} 表示这份 JSON 不是一个目标（缺 id 或 objective）
     */
    static SessionGoal fromJson(String sessionKey, String json) {
        if (json == null || json.isBlank() || "null".equals(json.trim())) {
            return null;
        }
        Map<?, ?> map;
        try {
            map = MAPPER.readValue(json, Map.class);
        } catch (JsonProcessingException malformed) {
            throw new IllegalArgumentException("目标 JSON 无法解析：" + malformed.getOriginalMessage(),
                    malformed);
        }
        Object id = map.get(ID);
        Object objective = map.get(OBJECTIVE);
        if (id == null || objective == null) {
            return null;
        }
        return new SessionGoal(
                sessionKey,
                integer(map, SCHEMA_VERSION, SessionGoal.SCHEMA_VERSION),
                UUID.fromString(id.toString()),
                objective.toString().trim(),
                GoalStatus.fromColumn(String.valueOf(map.get(STATUS))),
                instant(map, CREATED_AT),
                instant(map, UPDATED_AT),
                number(map, TOKEN_START, 0L),
                bool(map, TOKEN_START_FRESH, true),
                number(map, TOKENS_USED, 0L),
                map.get(TOKEN_BUDGET) == null ? null : number(map, TOKEN_BUDGET, 0L),
                integer(map, CONTINUATION_TURNS, 0),
                map.get(NOTE) == null ? null : map.get(NOTE).toString(),
                instant(map, PAUSED_AT),
                instant(map, BLOCKED_AT),
                instant(map, COMPLETED_AT),
                instant(map, USAGE_LIMITED_AT),
                instant(map, BUDGET_LIMITED_AT));
    }

    private static Long millis(Instant value) {
        return value == null ? null : value.toEpochMilli();
    }

    private static Instant instant(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : Instant.ofEpochMilli(((Number) value).longValue());
    }

    private static long number(Map<?, ?> map, String key, long fallback) {
        Object value = map.get(key);
        return value == null ? fallback : ((Number) value).longValue();
    }

    private static int integer(Map<?, ?> map, String key, int fallback) {
        Object value = map.get(key);
        return value == null ? fallback : ((Number) value).intValue();
    }

    private static boolean bool(Map<?, ?> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value == null ? fallback : (Boolean) value;
    }
}
