package io.github.claw4j.domain.principal;

import java.util.Set;

/**
 * 工具画像：一个部署"长什么样"，以及一个工具"属于哪几个画像"（P09 / ADR-0030 裁决一）。
 *
 * <p>四档是<b>四个互不包含的显式集合</b>，不是阶梯。每个工具声明自己在哪几个画像里
 * （见 {@link ToolTierRegistry#profilesFor}），部署选一个画像，声明里含这个画像的工具才进清单。
 *
 * <p><b>为什么不再用"最低需要哪一档"</b>：阶梯语义下 {@code CODING} 必然包含 {@code MESSAGING}
 * 的全部成员，于是做不出"能查数但碰不到机器"的部署——而那正是客服型部署的形状。更糟的是它
 * 无法表达"一个工具在两头、不在中间"：外部工具（业务模块、MCP）在 {@code coding} 与
 * {@code messaging} 都有、在 {@code minimal} 没有，阶梯表达不了。这是一次真实的反转，
 * 旧的"用最低档因为值能被穷举校验"那段理由随之作废：集合的表达力是这里的前提，配错由
 * {@link ToolProfile#select} 的未声明校验与启动日志兜。
 *
 * <p>四个画像各是什么（按"要不要碰机器 / 外部世界 / 自主行动"分）：
 * <ul>
 *   <li>{@link #MINIMAL}——地基：检索、问人、回忆本会话、记计划、读自己的状态。任何画像都含它。</li>
 *   <li>{@link #MESSAGING}——能问、能查，<b>碰不到机器</b>：地基 + 外部工具，没有 shell、
 *       没有文件、没有定时任务、没有子 Agent。</li>
 *   <li>{@link #CODING}——开发助手：全部内置能力 + 外部工具。</li>
 *   <li>{@link #FULL}——不限制（{@code allow: ["*"]}）。它不是"最全的画像"，是"不筛"。</li>
 * </ul>
 *
 * <p>外部工具（业务模块提供的工具、配置来的 MCP 工具）声明为 {@code {CODING, MESSAGING}}：
 * 它们能查数据、能调远端，但不会碰本机。这与 OpenClaw 把 {@code bundle-mcp} 同时放进
 * {@code coding} 与 {@code messaging} 是同一件事。
 *
 * <p>哪个工具属于哪几个画像不在这里，由提供工具的模块声明：只有它自己知道那个工具会碰到什么。
 */
public enum ToolTier {

    /** 地基：不碰外部世界、不碰业务数据。任何画像都含它。 */
    MINIMAL,

    /** 能问、能查，碰不到机器：地基 + 外部工具。 */
    MESSAGING,

    /** 开发助手：碰机器（文件、shell、网络）、能自主行动（定时任务、子 Agent）、能用技能。 */
    CODING,

    /** 不限制。不是"最全的画像"，是"不筛"。 */
    FULL;

    /**
     * 四个画像全含。用于"地基工具"与"哪一档都不该少"的那些。
     *
     * @return 全部画像
     */
    public static Set<ToolTier> all() {
        return Set.of(values());
    }
}
