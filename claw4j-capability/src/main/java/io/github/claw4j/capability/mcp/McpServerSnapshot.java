package io.github.claw4j.capability.mcp;

import java.time.Instant;
import java.util.List;

/**
 * 一个 MCP server 的当下状况：启动日志那一行、以及断言要读的东西。
 *
 * <p>为什么要把它做成公开类型而不是留在 {@link McpToolProvider} 私有：判据里有几条
 * （"掉线保留上次清单"、"空清单归可恢复"）只有把"状态"和"清单"分开看才验得出来——
 * 只看工具在不在，分不清"连上了但没工具"与"连不上但记着上次的"。
 *
 * @param name       配置里的 server 名
 * @param status     见 {@link McpServerStatus}
 * @param tools      当下对外可见的工具名；{@code DOWN} 时会保留最后一次成功列出的那些
 * @param attempts   已经排过几次重试（成功后清零）
 * @param nextRetryAt 下一次重试的时刻；不在重试中时为 null
 * @param lastError   最近一次失败的原因（一句话）；正常时为 null
 */
public record McpServerSnapshot(String name,
                                McpServerStatus status,
                                List<String> tools,
                                int attempts,
                                Instant nextRetryAt,
                                String lastError) {

    public McpServerSnapshot {
        tools = List.copyOf(tools);
    }

    /** 启动日志里那一小段：{@code calc → UP(工具 4 个)} / {@code broken → DOWN(30 秒后重试)}。 */
    public String describe(Instant now) {
        return switch (status) {
            case UP -> name + " → UP(工具 " + tools.size() + " 个)";
            case MISCONFIGURED -> name + " → 配置错误(" + lastError + ")，不再重试";
            case DOWN -> name + " → DOWN(" + remainingSeconds(now) + " 秒后重试)";
        };
    }

    private long remainingSeconds(Instant now) {
        if (nextRetryAt == null) {
            return 0;
        }
        // 向上取整：排的时候是"30 秒后"，过 0.8 秒再打日志不该变成"29 秒后"——
        // 日志里那个数字是给人读的，读的人会拿它去对退避序列。
        long millis = nextRetryAt.toEpochMilli() - now.toEpochMilli();
        return Math.max((millis + 999) / 1000, 0);
    }
}
