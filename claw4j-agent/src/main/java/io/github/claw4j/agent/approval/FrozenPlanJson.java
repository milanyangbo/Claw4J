package io.github.claw4j.agent.approval;

import io.github.claw4j.capability.exec.FrozenPlan;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 冻结件在库里的样子：{@code {"command": "...", "cwd": "..."}}（{@code frozen_plan} 那一列）。
 *
 * <p>为什么编解码放在 agent 而不是 capability：{@code FrozenPlan} 回答"批准的是哪一条命令"，
 * 是判定用的值对象；它存成什么形状是这张表的事。把 JSON 塞进值对象，能力层就要认识一个它
 * 只在持久化时才需要的库列。
 *
 * <p>解不出来时返回 {@code null}，不抛：一行读不懂的冻结件等价于"这条批准说不清批的是哪条命令"，
 * 按不放行处理——与空串指纹同一个取向。
 */
final class FrozenPlanJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private FrozenPlanJson() {
    }

    /**
     * 编码。
     *
     * @param plan 冻结件，可以为 {@code null}
     * @return JSON；{@code null} 入参返回 {@code null}
     */
    static String encode(FrozenPlan plan) {
        if (plan == null) {
            return null;
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("command", plan.command());
        node.put("cwd", plan.cwd());
        return node.toString();
    }

    /**
     * 解码。
     *
     * @param json 库里的那一列，可以为 {@code null}
     * @return 冻结件；为空、不是对象、或缺command 时为 {@code null}
     */
    static FrozenPlan decode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject() || !node.hasNonNull("command")) {
                return null;
            }
            return new FrozenPlan(node.get("command").asText(), node.path("cwd").asText(""));
        }
        catch (Exception unreadable) {
            return null;
        }
    }
}
