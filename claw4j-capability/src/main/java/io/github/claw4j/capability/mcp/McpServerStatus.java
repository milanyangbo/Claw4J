package io.github.claw4j.capability.mcp;

/**
 * 一个 MCP server 在这一刻的状态。
 *
 * <p>三档而不是两档：{@link #MISCONFIGURED} 与 {@link #DOWN} 的处理完全不同——
 * 前者重试一万次也不会变（yml 写错了），所以停手并报 ERROR；后者等一会儿会好，按退避继续试。
 * 把两者合成一个"不可用"，运维就只能从日志措辞里猜该不该改配置。
 */
public enum McpServerStatus {

    /** 连上了，工具清单是当下这一份。 */
    UP,

    /** 暂时不可用（连不上、握手超时、连上了但没导出工具）。后台按退避继续试。 */
    DOWN,

    /** 连上了但配置对不上（点名了它没导出的工具、与别的 server 重名）。不再重试，等人改配置。 */
    MISCONFIGURED
}
