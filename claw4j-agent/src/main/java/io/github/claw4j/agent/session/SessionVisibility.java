package io.github.claw4j.agent.session;

import java.util.Locale;

/**
 * 会话工具能看见哪些会话（票 05，照 OpenClaw 的 {@code tools.sessions.visibility}）。
 *
 * <p>四个值是 {@code self} / {@code tree} / {@code agent} / {@code all}，与它的定义逐字对应
 * （{@code docs/gateway/config-tools/sessions-and-subagents.md:128-133}）。照它抄的好处是排查时
 * 可以直接对着它的文档读本仓库的配置。
 *
 * <p>默认 {@link #ALL}，这也是它的默认值（"Default: {@code all} (every session on the Gateway,
 * including other agents' and other users' transcripts)"，
 * {@code docs/gateway/config-tools/sessions-and-subagents.md:110}）。不因为"安全一点"就默认收窄：
 * 默认值一变，部署就从"配错才出事"变成"配了才可用"。
 *
 * <p>代价写在明处：默认 {@code all} 意味着一个 {@code read-only} 身份可以召回另一个用户的会话
 * 内容。它清楚这一层（{@code docs/concepts/session-search.md:31-35} 写着 "including other
 * users' conversations"），仍然把 {@code all} 定成默认——安全位在配置项本身，不在默认值上。
 * 本仓库对齐这一点，另加两件它没有的（启动日志一行、yml 与 README 写明多角色部署必须配
 * {@code self}），见 {@link SessionVisibilityProperties}。
 *
 * <p>{@code tree} 与 {@code self} 今天完全同行为：子 Agent 会话不落会话库
 * （{@code SubagentRunner} 用的是 {@code ToolIndex} 的桶，不是 {@code ai_session}），
 * 所以"当前会话 + 它派生的会话"退化成"只有当前会话"。差集今天为空，等子 Agent 会话落库之后
 * 再改这一处。
 *
 * <p>{@code agent} 与 {@code all} 今天也同行为：只有一个 agent 命名空间，"本 agent 的全部会话"
 * 与"本部署的全部会话"是同一个集合。它们的差集要等第二个 agent 出现才成立。
 */
public enum SessionVisibility {

    /** 只有当前这一段会话。 */
    SELF("self"),

    /** 当前会话 + 它派生的子会话（今天同 {@link #SELF}，见类注释）。 */
    TREE("tree"),

    /** 本 agent 的全部会话，可以含其他用户的（今天同 {@link #ALL}）。 */
    AGENT("agent"),

    /** 全部会话，跨 agent 的那一层由别的开关管（本仓库只有一个 agent 命名空间）。 */
    ALL("all");

    /** 缺键时的生效值。它的默认是 {@code all}，这里照抄（见类注释）。 */
    public static final SessionVisibility DEFAULT = ALL;

    private final String key;

    SessionVisibility(String key) {
        this.key = key;
    }

    /** 配置里写的那个词。 */
    public String key() {
        return key;
    }

    /**
     * 是否跨会话检索。
     *
     * <p>{@code true} 的两档要走一条跨会话查询（{@link CrossSessionEvents}）；
     * {@code false} 的两档走框架的 {@code getEvents(sessionId, …)}。
     *
     * @return {@code agent} 与 {@code all} 为 true
     */
    public boolean crossSession() {
        return this == AGENT || this == ALL;
    }

    /**
     * 是否核会话归属前缀。
     *
     * <p>跨会话档不核：那两档本来就允许查别人的会话，再核前缀等于把范围又收回 {@code self}。
     * 但身份仍然不能为空——没有身份就没有"谁在问"这件事。
     *
     * <p>包内可见：判定只应该发生在 {@link SessionRecallScope#admit} 里，调用点自己读这个值
     * 就等于把"哪一档核不核"散到第二处，加第五档时又会漏改。
     *
     * @return {@code self} 与 {@code tree} 为 true
     */
    boolean checksOwnership() {
        return !crossSession();
    }

    /**
     * 启动日志与工具描述里给人看的那句话。
     *
     * <p>{@link #TREE} 与 {@link #SELF} 说同一句：它们今天就是同一件事。给它单独一句"暂等同
     * self"会让"没找到"的文案变长，而那句话是给模型读的，括号里的实现原因对它没有用。
     *
     * @return 这一档的一句话说明
     */
    public String description() {
        return switch (this) {
            case SELF, TREE -> "只有当前这一段对话";
            case AGENT -> "本 agent 的全部会话，含其他用户的";
            case ALL -> "全部会话，含其他用户的";
        };
    }

    /**
     * 配置值归一：缺键落 {@link #DEFAULT}，写错的词抛异常。
     *
     * <p>写错的词不回落成默认：OpenClaw 的 {@code resolveSessionToolsVisibility} 会
     * {@code return "all"}（{@code src/plugin-sdk/session-visibility.ts:137}），也就是把
     * {@code everybody} 这种词当成 {@code all}。那是 fail-open：配置写错变成最宽的授权，
     * 而"写错了"与"写的是 all"在日志上完全一样。本仓库故意不照抄这一处，见票 05 的偏离记录。
     *
     * @param raw 配置里的原值，可以为 null
     * @return 四个档位之一；null 或空串返回 {@link #DEFAULT}
     * @throws IllegalArgumentException 不是那四个词时。消息里点名键名与四个合法值——报错只说
     *                                  "转换失败"的话，运维还得去翻文档才知道能填什么
     */
    public static SessionVisibility of(String raw) {
        String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return DEFAULT;
        }
        return switch (value) {
            case "self" -> SELF;
            case "tree" -> TREE;
            case "agent" -> AGENT;
            case "all" -> ALL;
            default -> throw new IllegalArgumentException(
                    "claw4j.tools.sessions.visibility 只能是 self / tree / agent / all，收到的是 "
                            + "「" + raw.strip() + "」");
        };
    }
}
