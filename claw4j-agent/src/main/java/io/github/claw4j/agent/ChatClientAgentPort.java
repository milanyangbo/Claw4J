package io.github.claw4j.agent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.advisor.PiiRedactor;
import io.github.claw4j.agent.observability.MeteredToolCallback;
import io.github.claw4j.agent.observability.SpanTracer;
import io.github.claw4j.agent.channel.DeliveryTarget;
import io.github.claw4j.agent.channel.ConversationDelivery;
import io.github.claw4j.agent.TaskLauncher;
import io.github.claw4j.agent.session.SessionEntries;
import io.github.claw4j.agent.session.SessionCompactor;
import io.github.claw4j.agent.audit.AuditErrorCode;
import io.github.claw4j.agent.audit.AuditEvent;
import io.github.claw4j.agent.goal.GoalCommand;
import io.github.claw4j.agent.goal.GoalStatus;
import io.github.claw4j.agent.goal.SessionGoal;
import io.github.claw4j.agent.goal.SessionGoalStore;
import io.github.claw4j.agent.audit.AuditStatus;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.agent.session.SessionCommand;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.agent.session.SessionScopeProperties;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link AgentPort} 的默认实现：把入站消息喂给 ChatClient，把回答包成出站消息。
 *
 * <p>这是 Agent 对外唯一的门，通道、测试、MCP 入口都走这里，于是「换编排方式」被收在模块内部，
 * 外部不受影响。
 *
 * <p>{@link #stream} 会阻塞到流结束才返回，由调用方决定在哪个线程调用它。飞书那种「立刻 ACK
 * 再异步回推」就靠这个：通道收到事件后立刻返回，把 stream 丢给业务线程池，结果再通过主动推送
 * 接口发回去（ADR-0009）。
 *
 * <p>中断契约（见 {@link AgentPort#stream}）靠 {@code blockLast()} 满足：等待时响应中断，
 * 客户端断开后通道 {@code cancel(true)}，这里立刻以异常结束，而不是把整条模型流烧完。
 */
public class ChatClientAgentPort implements AgentPort, TaskLauncher {

    private static final Logger log = LoggerFactory.getLogger(ChatClientAgentPort.class);

    /** step 摘要的字符上限。 */
    private static final int SUMMARY_CHARS = 500;

    /** 接上时给模型回看几步。 */
    private static final int RECAP_STEPS = 5;

    /** recap 里每一步与目标的字符上限。 */
    private static final int RECAP_CHARS = 120;

    /**
     * 注入的「活跃目标」那一行最多几个字。
     *
     * <p>与 OpenClaw 的 {@code MAX_ACTIVE_GOAL_OBJECTIVE_CHARS} 同值（200，
     * {@code src/auto-reply/reply/inbound-meta.ts:29}），超出截断并加省略号。它只是一行提醒，
     * 不是目标全文——要看全文走 {@code /goal} 或 {@code get_goal}。
     */
    private static final int GOAL_CHARS = 200;

    /**
     * 根 span 的名字：一轮的执行体（P14）。
     *
     * <p>不是「一次请求」：五个通道都把 {@code agent.reply} 丢进线程池，请求的 trace 在入池那一刻
     * 就断了，而模型轮与工具调用都发生在入池之后。根放在这一轮上，trace 的边界才与审计里那两条
     * {@code agent_run} 事件之间是同一段。
     *
     * <p>编号只活一轮（ADR-0027）：{@code runId} 是这一轮的身份，不是能接上的把手，下一轮是新编号，
     * 同一段对话靠 {@code sessionKey} 连起来。
     */
    private static final String RUN_SPAN = "run";

    /** 一轮动作的 span：包住 {@code call()} 与收尾。工具 span 都挂在它下面。 */
    private static final String TURN_SPAN = "model.turn";

    /**
     * 这一轮是哪一轮。trace 与审计之间只有这一把键：拿它去 {@code audit_event.run_id} 查，
     * 这一轮的全部事件都在。
     */
    private static final String ATTR_RUN_ID = "claw4j.run.id";

    /**
     * 谁的这件事——只放权限指纹，不放 {@code userId}。
     *
     * <p>trace 会进第三方后端，而「哪个员工问了什么」本身是内部信息。指纹能回答「这两个 span
     * 是同一个人吗」（那正是排查时要问的），却反推不出是谁。与审计表里存 userId 的区别正是
     * 「审计归自己、trace 可能出去」。
     */
    private static final String ATTR_OWNER = "claw4j.owner";

    /** 这一轮花掉的 token。拿不到时不写——写 0 会被读成「没花钱」。 */
    private static final String ATTR_TOKENS = "claw4j.tokens";

    /** 这一轮是哪个模型答的。取响应里的 model id，不是配置里的路由名（路由在这一层看不到）。 */
    private static final String ATTR_MODEL = "gen_ai.response.model";

    /**
     * 撞上工具调用上限时回给用户的那一句。
     *
     * <p>只说「停了」不够：用户会以为是自己那句话有问题。要说清是这个动作被反复调了太多次，
     * 以及下一步能做什么（把要求说细一点）。
     */
    static final String TOOL_CALL_CAPPED =
            "这一步做了太多次，我先停下。请把要求说得更具体一些（查哪张表、哪段时间），"
                    + "我再试一次。";

    private final ChatClient chatClient;

    /**
     * 压缩器。可以为 null：没装配会话存储就没有「历史」这回事，也就没有可压的。这不是配置错误，
     * 是能力不在场，所以不造一个空实现去假装它存在。
     */
    private final SessionCompactor compactor;

    /**
     * 结果投递与回收（A05）。可以为 null：没有 run 存储就没有「送到哪儿」这回事，那是能力不在场，
     * 同样不造空实现。
     */
    private final ConversationDelivery delivery;

    /**
     * trace（P14）。默认是空实现：没开观测时每次 {@code open} 都是一次会被内联掉的调用，埋点代码
     * 与开着时逐字相同。关着不该是另一条代码路径，否则那条路径平时不跑，等开了观测才第一次执行。
     */
    private final SpanTracer tracer;

    /**
     * 这段对话现在叫什么（ADR-0026）。可以为 null：没有会话存储就没有「段」这回事，于是
     * {@code sessionId} 退回加段之前的形状。
     *
     * <p>它不属于 run 那一组：一轮的编号活一轮，段跨多轮，寿命不同，开关也不同——关掉那层持久化时
     * 段照常工作。
     */
    private final SessionEntries pointers;

    /**
     * 一段对话的串行化（ADR-0026 决定二）。可以为 null：没有它时两条并发消息各跑各的，也就是串行化
     * 之前的行为。这不是降级更好，是让只装配了 ChatClient 的调用方（例子、测试）不必凭空造一个
     * 调度器。
     *
     * <p>它必须包在最外层，不能包在 {@code promptOf} 里：套在里面时 run 已经开出来了，审计里会多出
     * 一条并行的事，「这段对话同时只有一个 run」就不成立。
     */
    private final ConversationLane lane;

    /** 会话目标（ADR-0027）。为 null 表示没开目标存储。 */
    private final SessionGoalStore goals;

    /**
     * 审计规则层（ADR-0027）：这一轮开始与结束各一条事件。
     *
     * <p>为 null 时一行都不写。它不是可选优化——没开审计的部署本来就该零写入，造一个空实现去假装
     * 它在，会让「审计到底有没有生效」没有答案。
     */
    private final ToolCallAudit audit;

    /**
     * 只连 ChatClient 的门：无压缩、无投递、无观测、无审计。
     *
     * @param chatClient 对话客户端，必填
     */
    public ChatClientAgentPort(ChatClient chatClient) {
        this(chatClient, null, null);
    }

    /**
     * 加上会话压缩的门。
     *
     * @param chatClient 对话客户端，必填
     * @param compactor 压缩器，可以为 null
     */
    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor) {
        this(chatClient, compactor, null);
    }

    /**
     * 加上结果投递的门。
     *
     * @param chatClient 对话客户端，必填
     * @param compactor 压缩器，可以为 null
     * @param delivery 结果投递，可以为 null
     */
    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor,
                               ConversationDelivery delivery) {
        this(chatClient, compactor, delivery, SpanTracer.NONE);
    }

    /**
     * 加上观测的门。
     *
     * @param chatClient 对话客户端，必填
     * @param compactor 压缩器，可以为 null
     * @param delivery 结果投递，可以为 null
     * @param tracer 为 null 时按空实现处理：装配里它走 {@code ObjectProvider}，没开观测的部署拿不到
     *     bean，而少一个 bean 不该让门都装不上
     */
    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor,
                               ConversationDelivery delivery, SpanTracer tracer) {
        this(chatClient, compactor, delivery, tracer, null);
    }

    /**
     * 加上会话段的门。
     *
     * @param chatClient 对话客户端，必填
     * @param compactor 压缩器，可以为 null
     * @param delivery 结果投递，可以为 null
     * @param tracer 埋点入口，为 null 时用 {@link SpanTracer#NONE}
     * @param pointers 会话指针，可以为 null
     */
    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor,
                               ConversationDelivery delivery, SpanTracer tracer,
                               SessionEntries pointers) {
        this(chatClient, compactor, delivery, tracer, pointers, null);
    }

    /**
     * 加上对话串行化的门。
     *
     * @param chatClient 对话客户端，必填
     * @param compactor 压缩器，可以为 null
     * @param delivery 结果投递，可以为 null
     * @param tracer 埋点入口，为 null 时用 {@link SpanTracer#NONE}
     * @param pointers 会话指针，可以为 null
     * @param lane 对话串行化，为 null 时不串行
     */
    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor,
                               ConversationDelivery delivery, SpanTracer tracer,
                               SessionEntries pointers, ConversationLane lane) {
        this(chatClient, compactor, delivery, tracer, pointers, lane, null);
    }

    /**
     * 加上审计的门。
     *
     * @param chatClient 对话客户端，必填
     * @param compactor 压缩器，可以为 null
     * @param delivery 结果投递，可以为 null
     * @param tracer 埋点入口，为 null 时用 {@link SpanTracer#NONE}
     * @param pointers 会话指针，可以为 null
     * @param lane 对话串行化，为 null 时不串行
     * @param audit 审计规则层（ADR-0027）。为 null 表示这个部署没开审计：判定与回答照常，只是事后
     *     查不到——与预算、投递同一个判断（能力不在场，不是假装它在）
     */
    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor,
                               ConversationDelivery delivery, SpanTracer tracer,
                               SessionEntries pointers, ConversationLane lane, ToolCallAudit audit) {
        this(chatClient, compactor, delivery, tracer, pointers, lane, audit, null);
    }

    /**
     * 完整装配。
     *
     * @param chatClient 对话客户端，必填
     * @param compactor 压缩器，可以为 null
     * @param delivery 结果投递，可以为 null
     * @param tracer 埋点入口，为 null 时用 {@link SpanTracer#NONE}
     * @param pointers 会话指针，可以为 null
     * @param lane 对话串行化，为 null 时不串行
     * @param audit 审计规则层，可以为 null
     * @param goals 会话目标的落点（ADR-0027 决定三）。为 null 表示这个部署没开目标存储：每回合少
     *     注入那一行，{@code /goal} 那族命令回一句「用不了」。它与「这一段没有目标」是两件事，
     *     前者是能力不在场，后者是常态
     */
    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor,
                               ConversationDelivery delivery, SpanTracer tracer,
                               SessionEntries pointers, ConversationLane lane, ToolCallAudit audit,
                               SessionGoalStore goals) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
        this.compactor = compactor;
        this.delivery = delivery;
        this.tracer = tracer == null ? SpanTracer.NONE : tracer;
        this.pointers = pointers;
        this.lane = lane;
        this.audit = audit;
        this.goals = goals;
    }

    /**
     * 这一轮的对话键：段由指针决定，指针缺席或读不到就是加段之前的形状。
     *
     * <p>抽出来是因为 {@code reply} 与 {@code stream} 两条路各要算一次，而它们必须算出同一个 id——
     * 一条消息走流式、下一条走非流式是常态，两处各写一遍的话，「什么算同一段对话」就有了两个答案。
     *
     * @param message 入站消息，可能自带整段会话键
     * @param owner 归属键
     * @param reset 是否换段，{@code /new} 时为真
     * @return 这一轮用的 sessionId
     */
    private String sessionIdOf(InboundMessage message, String owner, boolean reset) {
        // 调用方直接给了整段会话键：用它，不换段也不查指针。
        // 两条路走这里——审批批准之后回到发起请求的那一段（ADR-0027），
        // 以及定时任务（每次触发自带一个一次性的键）。
        if (message.sessionId() != null && !message.sessionId().isBlank()) {
            return message.sessionId();
        }
        String peer = conversationOf(message);
        // 指针缺席或读不到时就是"不加段"：段回答"这段对话叫什么"，不是"能不能回答"。
        String segment = pointers == null
                ? ""
                : reset
                        ? pointers.rotate(owner, message.direct(), message.channel(), peer)
                        : pointers.segmentOf(owner, message.direct(), message.channel(), peer);
        return SessionKeys.sessionIdOf(conversationKeyOf(message, owner), segment);
    }

    /**
     * 光发了一个 {@code /new}：换段，回一句确认。
     *
     * <p>不开 run、不调模型、不写事件：这一轮除了「新的开始了」没有别的信息，一次模型调用不值。
     * 而换段本身已经发生了，下一句自然落在新段上。
     *
     * <p>它在 lane 里被调用（{@code answer} 与 {@code streamInLane} 都在锁内），所以它与同一对话的
     * 另一条消息不会各换一段。
     *
     * @param message 入站消息
     * @return 固定确认文案
     */
    private String startOver(InboundMessage message) {
        // 走同一个入口，不下第二遍手：换段只有 sessionIdOf 一处实现，
        // 否则"单独 /new"与"/new 带内容"会各自长出一套，改一处忘一处。
        // 返回值是这一句本该用的新 sessionId——而这一句不落库、不进提示词，所以丢掉。
        sessionIdOf(message, SessionKeys.ownerOf(message.principal()), true);
        return SessionCommand.ACK;
    }

    /**
     * 这一轮属于哪个会话节点（不含段）。目标与状态行都按它定位。
     *
     * <p><b>这是键形状的唯一一处定义。</b>含段的 {@link #sessionIdOf} 也是先拿到它再拼段——
     * 两边各写一份的话，会出现"记忆落在 {@code ...:main:<段>}、目标落在
     * {@code ...:<channel>:<chatId>}"，两个节点都不是对方，而报错一个也没有：
     * {@code /goal} 只会说"这个会话还没有目标"。ADR-0029 把 peer 段交给 dmScope 之后，
     * 这个分叉不再是"某个配置下才发生"，默认配置下就是它。
     *
     * <p>不复用 sessionId 去解析：{@code chatId} 里可以有冒号（定时任务就是
     * {@code cron:<taskId>:<时刻>}），按分隔符切会在那种 id 上切错，而切错的表现是目标挂到了另一个
     * 节点上，不报错，只是读到空。
     */
    private String conversationKeyOf(InboundMessage message, String owner) {
        SessionScopeProperties scopes = pointers == null
                ? SessionScopeProperties.defaults()
                : pointers.scopes();
        return SessionKeys.conversationKeyOf(
                owner, message.direct(), message.channel(), conversationOf(message), scopes);
    }

    /**
     * 这段对话的身份：调用方给了 {@code conversationId} 就用它，没给就按 {@code chatId} 派生
     * （ADR-0026 决定三）。
     *
     * <p>为什么 chatId 不再兼任：它同时要回答「结果送到哪儿」——审批卡片、续跑结果、通知策略的推送
     * 全走它，而定时任务要的是「跑在自己的会话里，结果仍送到原来那个地方」。一个字段担两件事时，
     * 改哪一件都会把另一件弄坏：把 chatId 换成 {@code cron:<taskId>}，审批卡片就会推进一个不存在的
     * 会话，而这一轮看起来一切正常。
     */
    private static String conversationOf(InboundMessage message) {
        String explicit = message.conversationId();
        return explicit == null || explicit.isBlank() ? message.chatId() : explicit;
    }

    /**
     * 跑一轮，就地返回这一轮的回答。
     *
     * <p>这一进一出之间没有「一件事」，只有一轮（ADR-0027）：拿到消息、生成编号、调模型、把回答
     * 交回去。编号不落任何表，答完即作废，连续性靠会话键而不靠它。
     *
     * <p>审计的两条事件包住整轮：进门 {@code agent.run.started}，出门
     * {@code agent.run.finished}。少了开始那一条，跑了多久、两条并发的先后都只能靠时间戳猜。
     *
     * <p>额度记在目标上（ADR-0027 决定五）：出门把这轮的 token 记到这段会话的目标上，撞上预算就进
     * {@code budget_limited}——那是目标的状态，不是这一轮的状态。「这一轮绕了太多次」是另一件事，
     * 由工具循环的熔断在上限处截断。
     *
     * <p>结果只回到它开始的那段对话（ADR-0027 决定四）：就地回答过的地址不再推，推一遍就是同一条
     * 内容出现两次；定时任务与审批续跑没有人等，才按通知策略推出去。
     *
     * @param message 入站消息
     * @return 出站消息，带这一轮的 {@code runId}
     */
    @Override
    public OutboundMessage reply(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        Turn turn = inLane(message, () -> answer(message, addressOf(message)));
        return OutboundMessage.of(message.chatId(), turn.text(), turn.runId());
    }

    /**
     * 这一轮在它所在对话的锁里跑（ADR-0026 决定二）。
     *
     * <p>为什么必须串行：会话记忆在 {@code before} 读、在 {@code after} 写。两条消息并发时，第二条
     * 拼提示词的那一刻第一条的答案还没落库，上下文里有问题、没有答案。这种失败看起来像模型偶尔
     * 忘事，不会有人想到根因是并发。
     *
     * <p>拿不到就回一句话，不抛：已经 ACK 的消息没有任何回音，用户只会以为机器人坏了。也不重试、
     * 不排队到磁盘——单实例是既定形态（ADR-0009），跨进程的队列属于 A12。
     *
     * @param message 入站消息
     * @param work 要跑的这一轮
     * @return 这一轮的结果；拿不到锁时是一句忙的回复，{@code runId} 为 null
     */
    private Turn inLane(InboundMessage message, Supplier<Turn> work) {
        if (lane == null) {
            return work.get();
        }
        String address = ConversationLane.addressOf(
                conversationKeyOf(message, SessionKeys.ownerOf(message.principal())));
        try {
            return lane.run(address, work);
        } catch (LaneBusyException busy) {
            log.warn("这段对话还在处理上一句，这一句没等到：chatId={}", message.chatId());
            return new Turn(null, ConversationLane.BUSY_REPLY);
        }
    }

    /**
     * 没有人说话，但有一件事该做了（P04）：到点的定时任务从这里进来。
     *
     * <p>与 {@link #reply} 只差一件事：这一轮有没有就地回答的地址。有的话那一个地址不再推，
     * 否则同一条内容会出现两遍；没有的话结果按通知策略送到登记过的每一个地址，而
     * {@link NotifyPolicy#SILENT} 下一条都不推，那是任务主人选的那一档。
     *
     * <p>开 run、鉴权、工具、预算、收尾全走同一条路：定时任务不是第二种执行方式，只是提问的人
     * 不在场的那一种。
     *
     * @param message 到点的那一次提问
     * @return 这一轮的回答；模型侧抛异常时为空
     */
    @Override
    public Optional<String> launch(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        // 没有"跑不跑得了"的前置条件了：开一轮不需要任何存储（ADR-0027），
        // 于是这里唯一能返回空的地方是模型那一侧抛了异常——那由调用方记一次失败。
        return Optional.ofNullable(inLane(message, () -> answer(message, null)).text());
    }

    /** 这一轮的结果：编号与回答。两个都可能为空——命令类回执既没有编号，也没有调模型。 */
    private record Turn(String runId, String text) {
    }

    /**
     * 跑一轮：开 run、调模型、记账与收尾。
     *
     * @param message 入站消息
     * @param answeredAt 这一轮已经就地回答过的地址；null 表示没有人在等结果（定时任务、服务端续跑），
     *     于是按策略推给每一个登记过的地址
     * @return 这一轮的回答；命令类回执没有 {@code runId}
     */
    private Turn answer(InboundMessage message, DeliveryTarget answeredAt) {
        SessionCommand.Parsed parsed = SessionCommand.parse(message.text());
        if (parsed.bareCommand()) {
            return new Turn(null, startOver(message));
        }
        String owner = SessionKeys.ownerOf(message.principal());
        // 对话键与归属键是两把：owner 管"这是谁的东西"，sessionId 管"这是哪一段话"。
        // 群聊与私聊必须是两段——同一份权限下，听众可以完全不同（ADR-0025）。
        // 段由指针给（ADR-0026）：跨天、空闲、或者用户发了 /new 就换一个。
        String sessionId = sessionIdOf(message, owner, parsed.reset());
        // 目标挂在会话节点上（不含段），与含段的 sessionId 不是同一个字符串。
        String conversationKey = conversationKeyOf(message, owner);
        // /goal 这一族与 /new 同层：认出来就当场办、回一句话，不进模型也不开 run
        // （OpenClaw：纯命令消息绕过队列和模型）。放在 sessionId 之后，是因为它改的
        // 就是这一段会话上的东西——/new /goal ... 这种组合因此落在新段上。
        Optional<GoalCommand> goalCommand = GoalCommand.parse(parsed.text());
        if (goalCommand.isPresent()) {
            return new Turn(null, GoalCommand.execute(goalCommand.get(), goals, conversationKey));
        }
        // 送模型的是命令之后的内容：/new 帮我查华南 的第一句是「帮我查华南」，
        // 命令本身不该出现在提示词里，也不该成为这一轮的 run 目标。
        String masked = PiiRedactor.mask(parsed.text());
        // 这一轮的编号（ADR-0027）：本地生成，不落任何表。
        // 它只在三处用——审计的两条事件、trace 的一段 span、返回给调用方的回执。
        // OpenClaw 的 runId 也是这个角色：agent RPC 返回它，agent.wait 可以等它
        // （docs/gateway/protocol/rpc-talk-config-and-agents.md:97），
        // 但没有任何"按 runId 取回一件事"的接口，因为一轮答完它就作废了。
        String runId = UUID.randomUUID().toString();
        // 审计的"这一轮开始"。与结束那条成对：只有结束的话，跑了多久、两条并发的先后都只能靠时间戳猜。
        AuditEvent.Scope auditScope = AuditEvent.Scope.of(runId, sessionId, message.principal(),
                message.channel());
        if (audit != null) {
            audit.runStarted(auditScope);
        }
        // 根 span 从"这一轮"开始，而不是从这次请求开始：模型轮与工具都发生在入池之后（见 RUN_SPAN）。
        try (SpanTracer.SpanScope run = tracer.open(RUN_SPAN,
                runAttributes(runId, message.principal()))) {
            try {
                ChatResponse response;
                String text;
                // 一轮的 span 包住 call() 与收尾，所以它量的是"这一轮从开跑到答完"的整段时长，
                // 含收尾那一次审计 INSERT（几十毫秒）。span 上不写账本行号：
                // 与审计的关联键只有 claw4j.run.id 一个（见 ATTR_RUN_ID）。
                try (SpanTracer.SpanScope turn = tracer.open(TURN_SPAN, Map.of())) {
                    response = promptOf(message, owner, sessionId, conversationKey,
                            promptText(conversationKey, masked), runId)
                            .call()
                            .chatResponse();
                    text = cappedText(response);
                    // 日志敢打全文，是因为脱敏已经在 Advisor 链上做完；哪天有人在链路之外打日志，
                    // 这里会立刻出现原始值。
                    log.debug("出站回答 chatId={} runId={} text={}", message.chatId(), runId, text);
                    closeTurn(auditScope, conversationKey, text,
                            pushTargetOf(answeredAt, message), message.notifyPolicy(),
                            tokensOf(response));
                    stampTurn(turn, response);
                }
                return new Turn(runId, text);
            } catch (RuntimeException | Error failure) {
                run.failed(failure);
                if (audit != null) {
                    audit.runFinished(auditScope, AuditStatus.FAILED, AuditErrorCode.RUN_FAILED);
                }
                throw failure;
            }
        }
    }

    @Override
    public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(onChunk, "onChunk");
        if (lane == null) {
            streamInLane(message, onChunk);
            return;
        }
        String address = ConversationLane.addressOf(
                conversationKeyOf(message, SessionKeys.ownerOf(message.principal())));
        try {
            lane.run(address, () -> {
                streamInLane(message, onChunk);
                return null;
            });
        } catch (LaneBusyException busy) {
            // 与 reply 同一条口径：说话，不沉默。最后那一帧空文本是"这一路结束"的约定，
            // 没有它，调用方的流永远不会完成。
            log.warn("这段对话还在处理上一句，这一路流式没等到：chatId={}", message.chatId());
            onChunk.accept(OutboundMessage.of(message.chatId(), ConversationLane.BUSY_REPLY, null));
            onChunk.accept(OutboundMessage.of(message.chatId(), "", null));
        }
    }

    /** 真正的流式那一轮。锁由 {@link #stream} 拿——放在这里就晚了，run 已经开出来了。 */
    private void streamInLane(InboundMessage message, Consumer<OutboundMessage> onChunk) {
        SessionCommand.Parsed parsed = SessionCommand.parse(message.text());
        if (parsed.bareCommand()) {
            // 与"忙"那条同形：一帧正文 + 一帧空文本，调用方靠后者收尾。
            onChunk.accept(OutboundMessage.of(message.chatId(), startOver(message), null));
            onChunk.accept(OutboundMessage.of(message.chatId(), "", null));
            return;
        }
        String owner = SessionKeys.ownerOf(message.principal());
        String sessionId = sessionIdOf(message, owner, parsed.reset());
        // 目标挂在会话节点上（不含段），与含段的 sessionId 不是同一个字符串。
        String conversationKey = conversationKeyOf(message, owner);
        Optional<GoalCommand> goalCommand = GoalCommand.parse(parsed.text());
        if (goalCommand.isPresent()) {
            // 与 /new 那条同形：一帧正文 + 一帧空文本，调用方靠后者收尾。
            onChunk.accept(OutboundMessage.of(message.chatId(),
                    GoalCommand.execute(goalCommand.get(), goals, conversationKey), null));
            onChunk.accept(OutboundMessage.of(message.chatId(), "", null));
            return;
        }
        String masked = PiiRedactor.mask(parsed.text());
        String runId = UUID.randomUUID().toString();
        AuditEvent.Scope auditScope = AuditEvent.Scope.of(runId, sessionId, message.principal(),
                message.channel());
        if (audit != null) {
            audit.runStarted(auditScope);
        }
        try (SpanTracer.SpanScope run = tracer.open(RUN_SPAN,
                runAttributes(runId, message.principal()))) {
            StringBuilder answer = new StringBuilder();
            try (SpanTracer.SpanScope turn = tracer.open(TURN_SPAN, Map.of())) {
                try {
                    promptOf(message, owner, sessionId, conversationKey,
                            promptText(conversationKey, masked), runId)
                            .stream()
                            .content()
                            // 每一块都带 runId：调用方可能只收到最后一块，收到中间块的也要能认出同一批
                            .doOnNext(chunk -> {
                                log.debug("出站分块 chatId={} runId={} chunk={}", message.chatId(), runId, chunk);
                                answer.append(chunk == null ? "" : chunk);
                                onChunk.accept(OutboundMessage.chunk(message.chatId(), chunk, runId));
                            })
                            .doOnComplete(() -> onChunk.accept(OutboundMessage.of(message.chatId(), "", runId)))
                            .blockLast();
                    // 流式这一路手上没有 ChatResponse（汇总 usage 在最后一帧，未必到得了），
                    // 所以 token 传 null 而不是 0：0 会被读成「这一轮没花钱」，
                    // 而这是「没有数」。代价是流式的用量不计入目标预算（见票 04 的「没做」一节）。
                    closeTurn(auditScope, conversationKey, answer.toString(),
                            pushTargetOf(addressOf(message), message), message.notifyPolicy(),
                            null);
                } catch (RuntimeException | Error failure) {
                    // 流断在这里：两段 span 都要标失败，否则图上它是一轮"正常结束"的回答。
                    turn.failed(failure);
                    run.failed(failure);
                    if (audit != null) {
                        audit.runFinished(auditScope, AuditStatus.FAILED, AuditErrorCode.RUN_FAILED);
                    }
                    throw failure;
                }
            }
        }
    }

    /**
     * 交给模型的文本。
     *
     * <p>只有两段：活跃目标 + 这一句。不拼 recap：上下文一直在会话里（ADR-0027 决定三），模型看到的
     * 就是同一段对话的前几轮，不需要再抄一遍。
     *
     * @param conversationKey 会话节点键，用于找出活跃目标
     * @param masked 已脱敏的这一句
     * @return 注入给模型的文本
     */
    private String promptText(String conversationKey, String masked) {
        return activeGoalLine(conversationKey) + masked;
    }

    /**
     * 每回合那一行「活跃目标」（ADR-0027 决定三，照 OpenClaw 的注入形态）。
     *
     * <p>只有 {@code active} 才注入（{@code src/auto-reply/reply/inbound-meta.ts:37}）。OpenClaw 的
     * 原话是「已暂停、已阻塞、预算受限、用量受限和已完成的目标不会被注入，因此操作员的停止操作会
     * 持续生效，直到目标恢复」。这一条是「叫停能生效」的唯一保证——注入只判有没有行的话，用户按了
     * 暂停，下一轮模型还在往那个方向推进，而界面上写着「已暂停」。
     *
     * <p>四个分句逐句照原文（{@code English: Active goal: <objective> — advance; keep active until
     * fully achieved; block only after the same blocker on 3 consecutive turns; after update_goal,
     * provide the requested visible final.}，{@code docs/tools/goal.md:201}）：推进、达成前保持活跃、
     * 同一个阻塞连续三轮才标 blocked、{@code update_goal} 之后仍要给最终回答。少一句就会长出对应的
     * 坏行为，尤其最后一句：模型标完 {@code complete} 就收工，用户看到的是一个状态变了、但没有回答
     * 的一轮。
     *
     * <p>折成一行：目标原文可能有七行，而这里要的是一行上下文。折行只影响注入，库里存的仍是原文。
     *
     * @param conversationKey 会话节点键
     * @return 要注入的那一行；没有活跃目标时是空串
     */
    private String activeGoalLine(String conversationKey) {
        if (goals == null || conversationKey == null) {
            return "";
        }
        return goals.of(conversationKey)
                .filter(SessionGoal::injected)
                .map(goal -> "活跃目标：" + oneLine(goal.objective(), GOAL_CHARS)
                        + " — 推进它；没完全达成之前保持活跃；同一个阻塞连续三个轮次都在才标 blocked；"
                        + "update_goal 之后仍要给出用户要的最终回答。\n")
                .orElse("");
    }

    /**
     * 把多行折成一行再截断，连续空白压成一个空格，首尾不留。
     *
     * @param value 原文，可为 null
     * @param max 字符上限
     * @return 折好的一行
     */
    private static String oneLine(String value, int max) {
        String flat = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /**
     * 记下这一轮花掉的 token，按策略投递，然后写收尾那条审计（A04）。
     *
     * <p>这里只记模型轮，工具调用不归它记：这一层看不到工具循环里调了什么，猜一个填上去比留空更糟。
     * 工具调用那一项由 {@code MeteredToolCallback} 在调用发生的地方扣额度（A04），并在同一处落一行
     * 审计（P07）。要回答「这一轮调了哪些工具」，读 step 表里的 {@link StepKind#TOOL_CALLS} 行，
     * 不要读这里。
     *
     * <p>这一轮花掉的 token 记到目标上，不记到会话上（ADR-0027 决定五）：OpenClaw 的额度是 goal
     * 的属性（{@code create_goal} 的 {@code token_budget}，{@code src/agents/tools/goal-tools.ts:36}），
     * 用完进 {@code budget_limited}；会话本身没有「额度」这回事。记账与判超在同一条 SQL 里（见
     * {@code JdbcSessionGoalStore#addTokens}）。用完额度不是「这一轮失败」，它是这件事停下来了——
     * 状态在目标上，不在一轮上。
     *
     * <p>不写摘要（ADR-0027 决定二）：要回答「这一轮说了什么」，读通道里的回答与
     * {@code audit_event} 的两条事件；要回答「模型看到了什么」，读会话历史。摘要是「一件事的第几步」
     * 那个形状的产物，这里没有那种形状。
     *
     * @param scope 审计作用域
     * @param conversationKey 会话节点键，用于记额度
     * @param answer 这一轮的回答全文
     * @param pushTo 要推的地址；null 表示不推
     * @param policy 通知策略
     * @param tokens 这一轮花掉的 token；拿不到时为 null
     */
    private void closeTurn(AuditEvent.Scope scope, String conversationKey, String answer,
                           DeliveryTarget pushTo, NotifyPolicy policy, Long tokens) {
        chargeGoal(conversationKey, tokens);
        if (delivery != null && pushTo != null) {
            delivery.announce(pushTo, policy, answer);
        }
        finishAudit(scope, AuditStatus.SUCCEEDED, null);
    }

    /**
     * 收尾那条审计事件。没开审计时什么都不写——账本序号没有任何人需要。
     *
     * @param scope 审计作用域
     * @param status 这一轮的结局
     * @param errorCode 失败时的错误码，成功时为 null
     */
    private void finishAudit(AuditEvent.Scope scope, AuditStatus status, AuditErrorCode errorCode) {
        if (audit != null) {
            audit.runFinished(scope, status, errorCode);
        }
    }

    /**
     * 这一轮要不要推、推到哪儿。
     *
     * <p>同步请求（HTTP、飞书消息）的回答已经从响应里返回了，再推一次就是同一条内容出现两遍，
     * 那时返回 null。定时任务与审批续跑没有人等结果，于是推到这段对话（ADR-0027 决定四：结果只回到
     * 它开始的地方，不回退到「最近用过的通道」）。
     *
     * @param answeredAt 已经就地回答过的地址
     * @param message 入站消息，作为推送地址的来源
     * @return 要推的地址；不推时为 null
     */
    private static DeliveryTarget pushTargetOf(DeliveryTarget answeredAt, InboundMessage message) {
        return answeredAt == null ? addressOf(message) : null;
    }

    /**
     * 这一轮来自哪段对话。没有通道名时是 null（老调用方与单测）。
     *
     * @param message 入站消息
     * @return 推送地址；通道名为空时为 null
     */
    private static DeliveryTarget addressOf(InboundMessage message) {
        String channel = message.channel();
        if (channel == null || channel.isBlank()) {
            return null;
        }
        return new DeliveryTarget(channel, message.chatId());
    }

    /**
     * 把这一轮花掉的 token 记到这段会话的目标上。
     *
     * <p>没有目标就不记：额度是目标的属性，没有目标就没有「这件事的预算」这回事。记到一个不存在的
     * 东西上，等于把成本口径又挪回会话。
     *
     * <p>记不进去不影响回答：目标存储降级（库连不上、迁移没跑过）时 {@code account} 返回空，
     * 这一轮照常收尾。少记一笔的代价远小于把用户的话打死，那种降级由 {@code claw4j.goal.errors}
     * 暴露。
     *
     * @param conversationKey 会话节点键
     * @param tokens 这一轮花掉的 token；null 表示没有数，不重算用量
     */
    private void chargeGoal(String conversationKey, Long tokens) {
        if (goals == null || conversationKey == null) {
            return;
        }
        goals.account(conversationKey, tokens)
                .filter(goal -> goal.status() == GoalStatus.BUDGET_LIMITED)
                .ifPresent(goal -> log.info("目标撞上 token 预算，进入 budget_limited：session={} used={}/{}",
                        goal.sessionKey(), goal.tokensUsed(), goal.tokenBudget()));
    }

    /**
     * 撞上工具调用上限时交给用户的那句话。
     *
     * <p>它是止损，不是额度（ADR-0027 决定五）：OpenClaw 用 {@code agents.defaults.timeoutSeconds}
     * 给整轮一个期限，这里没有 per-turn 超时，所以需要一个更早的止损点。它不改任何持久状态——额度是
     * 目标的 token budget，而「这一轮绕了太多次」跟「这件事花超了」是两件事：前者要收手，后者要人决定
     * 加不加预算。
     *
     * <p>为什么在这里换掉那句话：上限由 Spring AI 的工具循环执行
     * （{@code ToolCallLimitBehavior.THROW}），它把超限变成一代回答，话是框架的英文原话。这里是唯一
     * 既知道「这是一次回答」又知道「它撞的是哪条线」的地方，换掉比让用户去读一句框架内部的话合适。
     *
     * <p>判定看 {@code finishReason} 这个公开常量，不做文本匹配：拿话术去认「这是不是超限」会在框架
     * 改一次措辞之后静默失效。
     *
     * @param response 模型响应，可为 null
     * @return 回答文本；撞上限时换成 {@link #TOOL_CALL_CAPPED}
     */
    private static String cappedText(ChatResponse response) {
        String text = textOf(response);
        boolean capped = response != null && response.getResult() != null
                && response.getResult().getMetadata() != null
                && ToolCallLimitExceededException.FINISH_REASON
                .equals(response.getResult().getMetadata().getFinishReason());
        return capped ? TOOL_CALL_CAPPED : text;
    }

    /**
     * 回答文本。没有 generation 时是 null，而不是造一个空串去冒充「模型说了话」。
     *
     * @param response 模型响应，可为 null
     * @return 回答文本；拿不到时为 null
     */
    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    /**
     * 这一轮花掉的 token。拿不到是 null，不是 0——不拿估算值冒充真实值（与
     * {@code MeteredChatModel} 同一个口径）。
     *
     * <p>两者必须分开：0 是「这一轮没花钱」，null 是「这一轮没有数」。目标记账拿 null 时不重算用量
     * （{@code JdbcSessionGoalStore#account}），拿 0 会把「没有数」记成「没花费」，而预算因此永远
     * 差一截，并且没有任何一处报错。
     *
     * @param response 模型响应，可为 null
     * @return 总 token 数；拿不到时为 null
     */
    private static Long tokensOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return null;
        }
        Integer total = response.getMetadata().getUsage().getTotalTokens();
        return total == null ? null : total.longValue();
    }

    /**
     * 把这一轮的结论写到 {@code model.turn} 上（P14）：花掉的 token、哪个模型答的。
     *
     * <p>两个值都「没有就不写」：token 拿不到时写 0 与「这轮没花钱」分不开，模型名同理。空属性是
     * 诚实的，补一个看起来合理的默认值才是——后者的代价是某天有人按这个数字判断成本。
     *
     * @param turn 这一轮的 span
     * @param response 模型响应，可为 null（流式那一侧拿不到汇总 usage）
     */
    private static void stampTurn(SpanTracer.SpanScope turn, ChatResponse response) {
        Long tokens = tokensOf(response);
        if (tokens != null && tokens > 0) {
            turn.attribute(ATTR_TOKENS, Long.toString(tokens));
        }
        String model = modelOf(response);
        if (model != null && !model.isBlank()) {
            turn.attribute(ATTR_MODEL, model);
        }
    }

    /**
     * 响应里报的模型名。不是配置里的路由名——路由在 {@code MeteredChatModel} 那一层，这里看不到。
     *
     * @param response 模型响应，可为 null
     * @return 模型名；拿不到时为 null
     */
    private static String modelOf(ChatResponse response) {
        return response == null || response.getMetadata() == null
                ? null : response.getMetadata().getModel();
    }

    /**
     * 根 span 的属性：哪一轮、谁的（指纹）。
     *
     * <p>为空时不写这一格：写一个空串会让「有编号」与「没有」在图上长得一样。
     *
     * @param runId 这一轮的编号，可为 null
     * @param principal 这一轮的身份，可为 null
     * @return 按插入顺序排列的属性表
     */
    private static Map<String, String> runAttributes(String runId, Principal principal) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (runId != null) {
            attributes.put(ATTR_RUN_ID, runId);
        }
        if (principal != null) {
            attributes.put(ATTR_OWNER, PermissionFingerprint.shortOf(principal));
        }
        return attributes;
    }

    /**
     * 拼出这一轮的请求：脱敏后的文本、工具上下文、Advisor 上下文。
     *
     * <p>身份与会话 id 走 Advisor 的上下文，不走 ThreadLocal：上下文会跟着
     * {@code ChatClientRequest} 走完工具循环的每一轮（每轮都把 {@code context} 原样带下去），也跟着
     * Reactor 的订阅走；ThreadLocal 在「流式 + 换线程」的组合下会掉，而掉身份的失败姿势是放行。
     *
     * <p>会话 id 由 {@link SessionKeys} 从 Principal 派生，不是 {@link InboundMessage#chatId()}：
     * 后者只是通道侧的寻址键，拿它当会话主键会让「换通道」变成「换会话」。链上没有记忆 Advisor 时
     * 这些参数被安静地忽略。
     *
     * <p>{@code runId} 两处都要放：工具上下文给工具里的写操作（计划，A03），Advisor 上下文给鉴权
     * 那一层（审批要挂起 run，P02）。只在工具上下文里放的话，Advisor 拦下工具调用时手里没有
     * {@code runId}，这一等就只能挂在库里没有落点的位置上。
     *
     * @param message 入站消息
     * @param owner 归属键
     * @param sessionId 本轮的会话 id
     * @param conversationKey 会话节点键（不含段）
     * @param masked 已脱敏的文本
     * @param runId 这一轮的编号，可为 null
     * @return 待执行的请求
     */
    private ChatClient.ChatClientRequestSpec promptOf(InboundMessage message, String owner, String sessionId,
                                                      String conversationKey, String masked, String runId) {
        Principal principal = message.principal();
        // 归属键与对话键都由调用方算好再传进来：一轮请求里 run 的 owner、会话 id
        // 都要用，各算一遍的话，口径一改就可能只改到一半，而表现是"会话记得住、run 查不到"。
        // 压缩必须发生在"这一轮拼提示词"之前：记忆 Advisor 在 before 阶段读事件，
        // 压完紧接着就被读到。放在 after 里等于压给下一轮用，而下一轮可能已经超了。
        if (compactor != null) {
            compactor.compactIfNeeded(sessionId);
        }
        // 入站就脱敏（ADR-0011 里真模型测试抓到的那个缺陷）：
        // 会话记忆是在 Advisor 链的 before 阶段最早那一步把用户消息写进库的，
        // 而出站脱敏发生在 after——它改得到模型的回答，改不到已经落库的那条用户消息。
        // 于是"出站是掩码、会话表里躺着明文"这种更持久的泄露就出现了，
        // 而且没有任何一条假模型的测试会发现它：假模型时代的用例从不往用户消息里放号码。
        //
        // 代价要记账：模型从此看不到用户写的原始号码。当前没有"按手机号查订单"这类工具，
        // 掩码不影响任何能力；哪天有了，得给那个工具开一条白名单，而不是把入站脱敏撤掉。
        Map<String, Object> toolContext = new HashMap<>();
        // 身份也放进工具上下文：委派（Subagent）发生在工具内部，
        // 那里拿不到 Advisor 上下文，只能从 ToolContext 里取。
        // 子 Agent 要用父这个身份继续鉴权——委派不提权，
        // 而"子那边拿不到身份"的失败姿势必须是拒绝（见 SubagentDelegation）。
        toolContext.put(AuthorizationAdvisor.PRINCIPAL_CONTEXT_KEY, principal);
        // 这一轮的编号同理：工具在工具内部，它要知道"我这一轮属于哪一次回答"。
        // 不放这个键时，工具那边读不到就是空——比放一个空串让它去猜清楚。
        if (runId != null) {
            toolContext.put(ContextKeys.RUN_ID, runId);
        }
        // 会话 id 也进工具上下文：recall_session 只查本段对话，它得知道"这一段"是哪一段。
        // 键用框架的 ChatMemory.CONVERSATION_ID，与它自己的会话工具（SessionEventTools）
        // 是同一个字符串——哪天换成框架那个工具，键名不必再改一次。
        // 放这里而不是让工具自己按 principal 推：会话键里还有通道与 chatId，工具推不出来，
        // 让它去猜就会退化成"这个人的所有会话"，而那正是私聊内容被带进群聊的那条路。
        toolContext.put(ChatMemory.CONVERSATION_ID, sessionId);
        // 会话节点键（不含段）也进工具上下文：目标挂在节点上，与记忆的按段隔离是两件事。
        // 让工具自己去 sessionId 里切段是不行的——chatId 里可以有冒号（cron: 就是）。
        toolContext.put(ContextKeys.CONVERSATION, conversationKey);
        // 通道与地址也进工具上下文（ADR-0027）：审批要写"这条请求来自哪段对话"，
        // 定时任务要写"结果推回哪里"。地址是这一轮的属性——不该绕一次库，也不该靠另一张表登记。
        if (message.channel() != null && !message.channel().isBlank()) {
            toolContext.put(ContextKeys.CHANNEL, message.channel());
        }
        if (message.chatId() != null && !message.chatId().isBlank()) {
            toolContext.put(ContextKeys.CHAT_REF, message.chatId());
        }
        return chatClient.prompt(masked)
                .toolContext(toolContext)
                .advisors(spec -> {
                    spec.param(AuthorizationAdvisor.PRINCIPAL_CONTEXT_KEY, principal)
                            .param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId)
                            // 外键用归属键而不是裸 userId：跨会话召回走 user_id 精确匹配，
                            // 传裸 userId 的话"换角色即换会话"在召回那条路上等于没做（见 SessionKeys）。
                            .param(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, owner);
                    // 审批那一层（AuthorizationAdvisor）读的是这份上下文，不是 ToolContext：
                    // 它要写"这条请求来自哪段对话"，于是这三个键两边都要有。
                    // 少了它们的表现是"批准之后说找不到来处"，而不是报错。
                    spec.param(ChatMemory.CONVERSATION_ID, sessionId);
                    if (message.channel() != null && !message.channel().isBlank()) {
                        spec.param(ContextKeys.CHANNEL, message.channel());
                    }
                    if (message.chatId() != null && !message.chatId().isBlank()) {
                        spec.param(ContextKeys.CHAT_REF, message.chatId());
                    }
                    // 鉴权那一层也要知道这是哪一轮（ADR-0027）：审批行上要记"这条请求是哪一轮发起的"，
                    // 那个答案只在这一格上。两处都要放——它读的是 Advisor 上下文，不是 ToolContext。
                    if (runId != null) {
                        spec.param(ContextKeys.RUN_ID, runId);
                    }
                });
    }
}
