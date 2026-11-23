package io.github.claw4j.domain;

/**
 * 一次调用里"谁在请求"这件事在两处上下文里的键名。
 *
 * <p>它必须定义在 domain 而不是 Agent 侧，理由是一个具体的失效：委派（Subagent）与脚本执行都
 * 发生在工具回调里，那里拿不到 Advisor 的上下文，只能从 {@code ToolContext} 里读身份；而工具在
 * {@code claw4j-capability}，按架构约束它不认识 {@code claw4j.agent}（ADR-0010）。于是这把 key
 * 只能住在两边都认识的地方。各写一份字符串常量，漂移的方式是"工具侧读到 null"，表现为
 * "委派/执行一律没身份"，而日志上只显示一次拒答。
 *
 * <p>Advisor 的 param 与 prompt 的 {@code toolContext} 用同一把 key，是有意的：两条路走到的是
 * 同一个 Principal，改一处就两边一起变。
 */
public final class ContextKeys {

    /** {@link Principal} 在 Advisor 上下文与工具上下文里的键名。 */
    public static final String PRINCIPAL = "claw4j.principal";

    /**
     * 这一段会话的提权档（{@code off} / {@code on} / {@code full}），在工具上下文里的键名。
     *
     * <p>为什么走工具上下文而不是让工具去查库：判定发生在 {layers} 的 {@code run(ExecSpec)} 里，
     * 那一刻手边只有命令；而"这一段对话刚才 /elevated 过"是会话状态，只有对话那一层知道。
     * 与 {@link #PRINCIPAL} 同一条理由——键名只能有一处定义，否则漂移的表现是"提权了但没生效"。
     */
    public static final String ELEVATION = "claw4j.elevation";

    /**
     * 这一轮的编号，在工具上下文里的键名（ADR-0027）。
     *
     * <p>它是句柄，不是一件事：一轮答完就作废，没有状态、没有后续，也没有"按 id 接上它"那条路。
     * 它只用于把同一轮的三样东西对起来——审计的两条事件、trace 的一段 span、返回给调用方的回执。
     *
     * <p>名字照 OpenClaw：它的 {@code runId} 也是这个角色，{@code chat.send} 返回它、
     * {@code chat.abort} 收它、{@code audit --run} 按它过滤，原话是"a possibly shared routing,
     * session, or recovery correlation"（docs/gateway/audit.md:106）。这边更窄，没有 recovery，
     * 也没有跨请求续跑，所以一轮就是一个 {@code runId}。同一个概念两边同名，客户端不用改字段。
     *
     * <p>入口写进去，不是模型传进来的：工具不接这个参数，模型因此没有"往别人的轮次上写东西"
     * 这条路径。放在这里而不是入口那个类上，是因为键名只有一处定义才不会漂移，而漂移的表现是
     * 工具读到 null，看起来像"这一轮没有编号"。
     */
    public static final String RUN_ID = "claw4j.runId";

    /**
     * 这一轮是哪个 agent 在跑，在工具上下文里的键名（ADR-0037 票 01）。
     *
     * <p>只给审计用：工具事件要能回答"这条是 ops 干的还是 complaint-writer 干的"。入口把部署级
     * agent 名写进来，工具只读——模型没有参数能改它，所以"把责任记到别的 agent 头上"这条路不存在。
     *
     * <p>取不到时写 {@code unknown} 而不是留空：空让「谁干的」不可查，而 OpenClaw 的
     * {@code agentId} 是必填（{@code audit.ts:53}）。
     */
    public static final String AGENT_ID = "claw4j.agentId";

    /**
     * 这一次工具调用在框架里的编号，在工具上下文里的键名（票 06）。
     *
     * <p>谁写进来：包装在工具回调外面的 {@code MeteredToolCallback}。它不从模型、也不从参数里取，
     * 只在框架已经建好的那次观测里读——{@code DefaultToolCallingManager} 调工具之前把
     * {@code toolCall.id()} 放进了 {@code ToolCallingObservationContext}，回调执行时它就在 scope 上。
     * 所以模型没有参数能改它，「把这次调用记成另一次」这条路不存在。
     *
     * <p>为什么不叫 {@code claw4j.tool_call_id}：与同族的 {@code runId} / {@code agentId} 一样用
     * 驼峰，读的人一眼看出这是「我们加的键」，不是 Spring AI 的保留名。
     *
     * <p>与 OpenClaw 的 {@code toolCallId} 同一个语义：标识<b>一次</b>调用，而不是一组参数。区别是
     * 那边由网关生成并一路带着，这边只能从观测层取；取不到（没开观测、非标准 provider）时键不存在，
     * 消费方按 {@code args_digest} 退回今天的行为。
     */
    public static final String TOOL_CALL_ID = "claw4j.toolCallId";

    /**
     * 这一轮从哪个通道来、通道内的哪段会话（ADR-0027）。
     *
     * <p>两个键解决同一类问题：审批要写"这条请求来自哪段对话"（批准之后回到那里开新一轮），
     * 定时任务要写"这个任务的结果推回哪里"。地址跟着这一轮走——它就写在入站消息上，
     * 不需要另一张表按 {@code runId} 去反查。
     *
     * <p>入口写进去，工具只读，与 {@link #PRINCIPAL} 同一处理方式。模型没有参数能改它们，
     * 于是"给别人的对话发审批卡片"这条路径不存在。
     */
    public static final String CHANNEL = "claw4j.channel";

    /** 通道内的会话地址。飞书是 {@code chat_id}，HTTP 是请求头里那个。 */
    public static final String CHAT_REF = "claw4j.chatRef";

    /**
     * 这一段对话的会话键，不含段（票 06）。目标与指针都按它定位。
     *
     * <p>{@link org.springframework.ai.chat.memory.ChatMemory#CONVERSATION_ID} 里放的是含段的
     * sessionId（框架的记忆按段隔离），而目标的状态挂在会话节点上、跨段只有一份。两者不是同一个
     * 字符串，所以不能用同一个键。
     *
     * <p>不从 sessionId 解析回来的理由：{@code chatId} 里可以有冒号（定时任务就是
     * {@code cron:<taskId>:<时刻>}），按分隔符切会在那种 id 上切错，而切错的后果是"目标挂到了
     * 另一个会话节点上"——不报错，只是读到空。
     */
    public static final String CONVERSATION = "claw4j.conversation";

    private ContextKeys() {
    }
}
