package io.github.claw4j.agent.session;

import java.util.Locale;

/**
 * 会话工具能看见哪些会话（票 05，照 OpenClaw 的 {@code tools.sessions.visibility}）。
 *
 * <p><b>四个值不是我们发明的档位</b>：它那四个值就是 {@code self} / {@code tree} / {@code agent} /
 * {@code all}，四个字一个字都不差（{@code docs/gateway/config-tools/sessions-and-subagents.md}
 * 的 "Visibility scopes" 一节）。照它抄的好处是排查时可以直接对着它的文档读我们的配置。
 *
 * <p><b>默认 {@link #ALL}</b>：这条是它的原文 "Default: {@code all} (every session on the Gateway,
 * including other agents' and other users' transcripts)"。我们照抄，不因为"我们觉得安全一点"
 * 就默认收窄——默认值一变，部署从"配错才出事"变成"配了才可用"，那是另一种不兼容。
 *
 * <p><b>它给的代价要写在明处</b>：默认 {@code all} 意味着一个 {@code read-only} 身份可以召回
 * 另一个用户的会话内容。它清楚这一层（{@code docs/concepts/session-search.md:32-38} 专门写了
 * "including other users' conversations"），仍然把 {@code all} 定成默认——安全位在配置项本身，
 * 不在默认值上。我们对齐，另加两件它没有的（启动日志一行、yml 与 README 写明多角色部署必须配
 * {@code self}），见 {@link SessionVisibilityProperties}。
 *
 * <p><b>{@code tree} 与 {@code self} 今天完全同行为</b>：我们的子 Agent 会话不落会话库
 * （{@code SubagentRunner} 用的是 {@code ToolIndex} 的桶，不是 {@code ai_session}），
 * 所以"当前会话 + 它派生的会话"退化成"只有当前会话"。这不是没实现，是那个差集今天为空——
 * 等子 Agent 会话落库之后再改这一处，别让下一个人以为两个值"应该有差别"却查不出来。
 *
 * <p><b>{@code agent} 与 {@code all} 今天也同行为</b>：我们只有一个 agent 命名空间，
 * "本 agent 的全部会话"与"本部署的全部会话"是同一个集合。它与 {@code tree}/{@code self}
 * 同行为不是一回事——那两个的差集是空的，这两个的差集要等第二个 agent 出现才成立。
 */
public enum SessionVisibility {

    /** 只有当前这一段会话。 */
    SELF("self"),

    /** 当前会话 + 它派生的子会话（今天同 {@link #SELF}，见类注释）。 */
    TREE("tree"),

    /** 本 agent 的全部会话，可以含其他用户的（今天同 {@link #ALL}）。 */
    AGENT("agent"),

    /** 全部会话，跨 agent 的那一层由别的开关管（我们只有一个 agent 命名空间）。 */
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
     */
    public boolean crossSession() {
        return this == AGENT || this == ALL;
    }

    /**
     * 是否核会话归属前缀。
     *
     * <p>跨会话档不核：那两档本来就允许查别人的会话，再核前缀等于把范围又收回 {@code self}。
     * 但<b>身份仍然不能为空</b>——不是用来限定范围，而是"没有身份就没有'谁在问'这件事"，
     * 那种请求连该不该给都要靠猜。
     *
     * <p>包内可见：判定只应该发生在 {@link SessionRecallScope#admit} 里，调用点自己读这个值
     * 就等于把"哪一档核不核"散到第二处，加第五档时又会漏改。
     */
    boolean checksOwnership() {
        return !crossSession();
    }

    /**
     * 启动日志与工具描述里给人看的那句话。
     *
     * <p>{@link #TREE} 与 {@link #SELF} 说同一句：它们今天就是同一件事（子 Agent 会话不落库）。
     * 给它单独一句"暂等同 self"会让"没找到"的文案跟着变长，而那句话是给模型读的，
     * 括号里的实现原因对它没有用——原因写在这个枚举的类注释里，给改代码的人看。
     */
    public String description() {
        return switch (this) {
            case SELF, TREE -> "只有当前这一段对话";
            case AGENT -> "本 agent 的全部会话，含其他用户的";
            case ALL -> "全部会话，含其他用户的";
        };
    }

    /**
     * 配置值归一：缺键落 {@link #DEFAULT}，写错的词**抛**。
     *
     * <p><b>写错的词不回落成默认</b>：它的实现是
     * {@code resolveSessionToolsVisibility} 里 {@code return "all"}（{@code src/plugin-sdk/session-visibility.ts:132-138}），
     * 也就是把 {@code everybody} 这种词当成 {@code all}。那是 fail-open：配置写错变成最宽的授权，
     * 而"写错了"与"写的是 all"在日志上完全一样。这里故意不照抄这一处，见票 05 的偏离记录。
     *
     * @throws IllegalArgumentException 不是那四个词时。消息里点名键名与四个合法值——
     *                                  报错只说"转换失败"的话，运维还得去翻文档才知道能填什么
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
