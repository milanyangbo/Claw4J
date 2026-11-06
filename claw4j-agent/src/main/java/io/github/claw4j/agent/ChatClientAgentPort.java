package io.github.claw4j.agent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.advisor.PiiRedactor;
import io.github.claw4j.agent.observability.MeteredToolCallback;
import io.github.claw4j.agent.observability.RunTracer;
import io.github.claw4j.agent.run.DeliveryTarget;
import io.github.claw4j.agent.run.Run;
import io.github.claw4j.agent.run.RunDelivery;
import io.github.claw4j.agent.run.RunStatus;
import io.github.claw4j.agent.run.RunStore;
import io.github.claw4j.agent.run.RunLauncher;
import io.github.claw4j.agent.run.Step;
import io.github.claw4j.agent.run.StepInput;
import io.github.claw4j.agent.run.StepKind;
import io.github.claw4j.agent.run.StepStatus;
import io.github.claw4j.agent.session.ConversationPointers;
import io.github.claw4j.agent.session.SessionCompactor;
import io.github.claw4j.agent.audit.AuditEvent;
import io.github.claw4j.agent.goal.GoalCommand;
import io.github.claw4j.agent.goal.GoalStatus;
import io.github.claw4j.agent.goal.SessionGoal;
import io.github.claw4j.agent.goal.SessionGoalStore;
import io.github.claw4j.agent.audit.AuditStatus;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.agent.session.SessionCommand;
import io.github.claw4j.agent.session.SessionKeys;
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
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link AgentPort} 的默认实现：把入站消息喂给 ChatClient，把回答包成出站消息。
 *
 * <p>这是整个 Agent 对外唯一的门。通道、测试、MCP 入口都走这里，
 * 于是"换编排方式"这件事被收在模块内部，外部不受影响。
 *
 * <p>{@link #stream} 会阻塞到流结束才返回。调用方（通道）自己决定在哪个线程调用它——
 * 飞书那种「立刻 ACK 再异步回推」的场景就靠这个：通道收到事件后立刻返回，
 * 把 stream 丢给业务线程池，结果再通过主动推送接口发回去（ADR-0009）。
 *
 * <p>中断契约（见 {@link AgentPort#stream}）靠 {@code blockLast()} 天然满足：
 * 它在等待流时响应中断。客户端断开后通道 {@code cancel(true)}，
 * 这里会立刻以异常结束，而不是把整条模型流烧完。
 */
public class ChatClientAgentPort implements AgentPort, RunLauncher {

    private static final Logger log = LoggerFactory.getLogger(ChatClientAgentPort.class);

    /** step 摘要的字符上限。 */
    private static final int SUMMARY_CHARS = 500;

    /** 接上时给模型回看几步。 */
    private static final int RECAP_STEPS = 5;

    /** recap 里每一步与目标的字符上限。 */
    private static final int RECAP_CHARS = 120;

    /**
     * 注入的"活跃目标"那一行最多几个字。
     *
     * <p>OpenClaw 会截断过长的目标内容"使该行保持紧凑"。它只是一行提醒，
     * 不是目标全文——要看全文走 {@code /goal} 或 {@code get_goal}。
     */
    private static final int GOAL_CHARS = 200;

    /**
     * 根 span 的名字：**一次 run 的执行体**（P14）。
     *
     * <p>不是"一次请求"：五个通道都把 {@code agent.reply} 丢进线程池，请求的 trace 在入池那一刻就断了；
     * 而 run 跨通道、跨重试、跨进程重启（resume 时还是同一个 runId）。根放在 run 上，
     * trace 的边界才与库里那张表的边界是同一件事。
     */
    private static final String RUN_SPAN = "run";

    /** 一轮动作的 span：包住 {@code call()} 与收尾。工具 span 都挂在它下面。 */
    private static final String TURN_SPAN = "model.turn";

    /** 这件事是哪一件。与 {@code MeteredToolCallback.ATTR_STEP_SEQ} 一起，能把 span 反查回库。 */
    private static final String ATTR_RUN_ID = "claw4j.run.id";

    /**
     * 谁的这件事——**只放权限指纹，不放 userId**。
     *
     * <p>trace 会进第三方后端，而"哪个员工问了什么"本身是内部信息。指纹能回答
     * "这两个 span 是同一个人吗"（那正是排查时要问的），却反推不出是谁——
     * 与审计表里存 userId 的区别正是"审计归自己、trace 可能出去"。
     */
    private static final String ATTR_OWNER = "claw4j.owner";

    /** 这一轮的审计行序号。span 与库唯一能对上号的地方。 */
    private static final String ATTR_STEP_SEQ = "claw4j.step.seq";

    /** 这一轮花掉的 token。拿不到时**不写**——写 0 会被读成"没花钱"。 */
    private static final String ATTR_TOKENS = "claw4j.tokens";

    /** 这一轮是哪个模型答的。取响应里的 model id，不是我们配的路由名（那一个在这一层看不到）。 */
    private static final String ATTR_MODEL = "gen_ai.response.model";

    /** 接上来的那一轮标一下：同一个 runId 会有多条 trace，这一条是"继续"，不是"开头"。 */
    private static final String ATTR_RESUMED = "claw4j.run.resumed";

    /**
     * 撞上工具调用上限时回给用户的那一句。
     *
     * <p>只说"停了"不够：用户会以为是自己那句话有问题。要说清<b>是这个动作被反复调了太多次</b>，
     * 以及<b>下一步能做什么</b>（把要求说细一点）。
     */
    static final String TOOL_CALL_CAPPED =
            "这一步做了太多次，我先停下。请把要求说得更具体一些（查哪张表、哪段时间），"
                    + "我再试一次。";

    private final ChatClient chatClient;

    /**
     * 压缩器。可以为 null——没装配会话存储时就没有"历史"这回事，也就没有可压的。
     * 这不是配置错误，是能力不在场，所以不在这里造一个空实现去假装它存在。
     */
    private final SessionCompactor compactor;

    /**
     * run 的落点。可以为 null——没装配存储时就没有"这件事做完了没有"这回事，
     * 那是能力不在场，不是配置错误，所以不在这里造一个空实现去假装它存在
     * （与 {@link #compactor} 同一个判断）。
     */
    private final RunStore runs;

    /**
     * 结果投递与回收（A05）。可以为 null——与上面两个同一个判断：没有 run 存储
     * 就没有"送到哪儿"这回事，那是能力不在场，不造一个空实现去假装它存在。
     */
    private final RunDelivery delivery;

    /**
     * trace（P14）。默认是空实现：没开观测时这里一次 {@code open} 就是一次内联掉的方法调用，
     * 埋点代码与开着的时候逐字相同——**"关着"不该是另一条代码路径**，
     * 否则那条路径平时不跑，等开了观测才第一次执行。
     */
    private final RunTracer tracer;

    /**
     * 这段对话现在叫什么（ADR-0026）。可以为 null——没有会话存储就没有"段"这回事，
     * 于是 {@code sessionId} 退回加段之前的形状，与改造之前逐字相同。
     *
     * <p><b>它<b>不</b>属于 run 那一组</b>：run 回答"这件事做完了没有"，段回答
     * "这是哪一段对话"。两者的寿命不同（run 一轮、段跨多轮），所以开关也不同——
     * {@code claw4j.run.enabled=false} 时 run 相关的一整组缺席，段照常工作。
     */
    private final ConversationPointers pointers;

    /**
     * 一段对话的串行化（ADR-0026 决定二）。可以为 null——没有它时两条并发消息各跑各的，
     * 也就是改造之前的行为。这不是"降级更好"，是让只装配了 ChatClient 的调用方
     * （例子、测试）不必凭空造一个调度器。
     *
     * <p><b>它包在最外层</b>，不是包在 {@code promptOf} 里：套在里面时 run 已经开出来了，
     * 审计里会多出一条并行的事，而"这段对话同时只有一个 run"这句话就不成立了。
     */
    private final ConversationLane lane;

    /** 会话目标（ADR-0027）。为 null 表示没开目标存储。 */
    private final SessionGoalStore goals;

    /**
     * 审计规则层（ADR-0027）：这一轮开始 / 结束各一条事件。
     *
     * <p>为 null 时一行都不写。它不是"可选优化"——没开审计的部署本来就该零写入，
     * 造一个空实现去假装它在，会让"审计到底有没有生效"这个问题没有答案。
     */
    private final ToolCallAudit audit;

    public ChatClientAgentPort(ChatClient chatClient) {
        this(chatClient, null, null, null);
    }

    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor) {
        this(chatClient, compactor, null, null);
    }

    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor, RunStore runs) {
        this(chatClient, compactor, runs, null);
    }

    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor, RunStore runs,
                               RunDelivery delivery) {
        this(chatClient, compactor, runs, delivery, RunTracer.NONE);
    }

    /**
     * @param tracer 为 null 时按空实现处理：装配里它走 {@code ObjectProvider}，
     *               没开观测的部署拿不到 bean，而"少一个 bean"不该让门都装不上
     */
    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor, RunStore runs,
                               RunDelivery delivery, RunTracer tracer) {
        this(chatClient, compactor, runs, delivery, tracer, null);
    }

    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor, RunStore runs,
                               RunDelivery delivery, RunTracer tracer,
                               ConversationPointers pointers) {
        this(chatClient, compactor, runs, delivery, tracer, pointers, null);
    }

    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor, RunStore runs,
                               RunDelivery delivery, RunTracer tracer,
                               ConversationPointers pointers, ConversationLane lane) {
        this(chatClient, compactor, runs, delivery, tracer, pointers, lane, null);
    }

    /**
     * @param audit 审计规则层（ADR-0027）。为 null 表示这个部署没开审计：判定与回答照常，
     *              只是事后查不到——与预算、投递同一个判断（能力不在场，不是假装它在）
     */
    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor, RunStore runs,
                               RunDelivery delivery, RunTracer tracer,
                               ConversationPointers pointers, ConversationLane lane, ToolCallAudit audit) {
        this(chatClient, compactor, runs, delivery, tracer, pointers, lane, audit, null);
    }

    /**
     * @param goals 会话目标的落点（ADR-0027 决定三）。为 null 表示这个部署没开目标存储：
     *              于是每回合少注入那一行、{@code /goal} 那族命令回一句"用不了"。
     *              注意它与"这一段没有目标"是两件事——前者是能力不在场，后者是常态
     */
    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor, RunStore runs,
                               RunDelivery delivery, RunTracer tracer,
                               ConversationPointers pointers, ConversationLane lane, ToolCallAudit audit,
                               SessionGoalStore goals) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
        this.compactor = compactor;
        this.runs = runs;
        this.delivery = delivery;
        this.tracer = tracer == null ? RunTracer.NONE : tracer;
        this.pointers = pointers;
        this.lane = lane;
        this.audit = audit;
        this.goals = goals;
    }

    /**
     * 这一轮的对话键：段由指针决定，指针缺席或读不到就是加段之前的形状。
     *
     * <p>抽出来是因为 {@code reply} 与 {@code stream} 两条路各要算一次，
     * 而它们必须算出<b>同一个</b> id——一条消息走流式、下一条走非流式是常态，
     * 两处各写一遍的话，"什么算同一段对话"就有了两个答案。
     */
    private String sessionIdOf(InboundMessage message, String owner, boolean reset) {
        // 调用方直接给了整段会话键：用它，不换段也不查指针。
        // 审批批准之后走的就是这条——回到发起请求的那一段对话里开新一轮（ADR-0027）。
        if (message.sessionId() != null && !message.sessionId().isBlank()) {
            return message.sessionId();
        }
        String conversation = conversationOf(message);
        // 调用方自带会话标识的那条路（定时任务）不走指针：它的"段"就是触发时刻本身，
        // 每次运行天然是一个新的。指针只服务"人"这条路——那里才有"上次说到哪儿"。
        String segment = pointers == null || message.conversationId() != null
                ? ""
                : reset
                        ? pointers.rotate(owner, message.channel(), conversation)
                        : pointers.segmentOf(owner, message.channel(), conversation);
        return SessionKeys.sessionIdOf(owner, message.channel(), conversation, segment);
    }

    /**
     * 光发了一个 {@code /new}：换段，回一句确认。
     *
     * <p><b>不开 run、不调模型、不写事件</b>：这一轮除了"新的开始了"没有别的信息，
     * 一次模型调用不值。而"换段"本身已经发生了——下一句自然落在新段上。
     *
     * <p>在 lane 里被调用（{@code answer} 与 {@code streamInLane} 都在门内），
     * 所以它与同一对话的另一条消息不会各换一段。
     */
    private String startOver(InboundMessage message) {
        // 走同一个入口，不下第二遍手：换段只有 sessionIdOf 一处实现，
        // 否则"单独 /new"与"/new 带内容"会各自长出一套，改一处忘一处。
        // 返回值是这一句本该用的新 sessionId——而这一句不落库、不进提示词，所以丢掉。
        sessionIdOf(message, SessionKeys.ownerOf(message.principal()), true);
        return SessionCommand.ACK;
    }

    /**
     * 这段对话的身份：调用方给了就用它，没给就按 {@code chatId} 派生（ADR-0026 决定三）。
     *
     * <p><b>为什么 chatId 不再兼任</b>：它同时要回答"结果送到哪儿"——审批卡片、续跑结果、
     * 通知策略的推送全走它。而定时任务要的是"跑在自己的会话里，结果仍送到原来那个地方"。
     * 一个字段担两件事时，改哪一件都会把另一件弄坏：把 chatId 换成 {@code cron:<taskId>}，
     * 审批卡片就会推进一个不存在的会话，而这一轮看起来一切正常。
     */
    private static String conversationOf(InboundMessage message) {
        String explicit = message.conversationId();
        return explicit == null || explicit.isBlank() ? message.chatId() : explicit;
    }

    /**
     * 出站日志敢打**全文**，是因为脱敏已经在 Advisor 链上做完了。
     * 反过来：这条日志也是"脱敏发生在出站之前而不是事后替换"的证据——
     * 哪天有人在链路之外打日志，这里会立刻出现原始值。
     *
 * <p>run 在这一进一出之间：进门开一个（或接上一个），答完记一步并收尾。
 * <b>开 run 发生在模型之前</b>，否则模型一崩就没有任何痕迹表明这件事发生过。
 *
 * <p>预算也按 run 累计（A04）：进门先看这一件还剩多少额度，出门扣掉这一轮花掉的
 * token 与耗时。工具调用那一项不在这里扣——它由 {@code MeteredToolCallback} 在每一次
 * 调用前扣，因为只有那里知道"这次调用真的发生了"。三项里任何一项撞线，
 * 这一件事就停在 {@link RunStatus#BUDGET_EXHAUSTED}，并把结局写进库里，
 * 而不是抛一个要上层猜的异常。
 *
 * <p>结果在这一进一出之间有了"送到哪儿"（A05）：进门登记收件地址，出门把回答全文
 * 记在 run 上，再按通知策略送到别的地址。<b>就地回答过的那个地址不再推</b>——
 * 推一遍就是同一条内容出现两次；别的地址照推，那正是"在飞书开的头、在别处收结果"。
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
     * <p><b>为什么必须串行</b>：会话记忆在 {@code before} 读、在 {@code after} 写。
     * 两条消息并发时，第二条拼提示词的那一刻第一条的答案还没落库——上下文里有问题、
     * 没有答案。而它长得像"模型偶尔会忘事"，不会有人想到根因是并发。
     *
     * <p><b>拿不到就回一句话，不抛</b>：已经 ACK 的消息没有任何回音，用户只会以为
     * 机器人坏了。也不重试、不排队到磁盘——单实例是既定形态（ADR-0009），
     * 跨进程的队列属于 A12。
     */
    private Turn inLane(InboundMessage message, Supplier<Turn> work) {
        if (lane == null) {
            return work.get();
        }
        String address = ConversationLane.addressOf(SessionKeys.ownerOf(message.principal()),
                message.channel(), conversationOf(message));
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
     * <p><b>与 {@link #reply} 只差一件事：这一轮有没有"就地回答"的地址</b>。
     * 有的话（有人正等着回答）那一个地址不再推，否则同一条内容会出现两遍；
     * 没有的话（定时任务）结果按通知策略送到登记过的每一个地址——
     * 而 {@link NotifyPolicy#SILENT} 下一条都不推，这是任务主人选的那一档。
     *
     * <p>开 run、鉴权、工具、预算、收尾全走同一条路：定时任务不是第二种执行方式，
     * 只是"提问的人不在场"的那一种。
     */
    @Override
    public Optional<String> launch(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        if (runs == null) {
            // 没有 run 存储就没有"这件事"可落，也就没有"跑完算谁的"这个问题。
            // 如实返回空，让调度器记一次失败——它会在三次之后停掉这条任务。
            return Optional.empty();
        }
        return Optional.ofNullable(inLane(message, () -> answer(message, null)).text());
    }

    /** 这一轮的结果：落在哪个 run 上、回答是什么。 */
    private record Turn(String runId, String text) {
    }

    /**
     * 跑一轮：开 run（或接上一个）→ 看额度 → 调模型 → 收尾。
     *
     * @param answeredAt 这一轮已经就地回答过的地址；null 表示没有人在等结果
     *                   （定时任务、服务端续跑），于是按策略推给每一个登记过的地址
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
        // /goal 这一族与 /new 同层：认出来就当场办、回一句话，不进模型也不开 run
        // （OpenClaw：纯命令消息绕过队列和模型）。放在 sessionId 之后，是因为它改的
        // 就是这一段会话上的东西——/new /goal ... 这种组合因此落在新段上。
        Optional<GoalCommand> goalCommand = GoalCommand.parse(parsed.text());
        if (goalCommand.isPresent()) {
            return new Turn(null, GoalCommand.execute(goalCommand.get(), goals, sessionId));
        }
        // 送模型的是命令之后的内容：`/new 帮我查华南` 的第一句是"帮我查华南"，
        // 命令本身不该出现在提示词里，也不该成为这一轮的 run 目标。
        String masked = PiiRedactor.mask(parsed.text());
        Run openedRun = openRun(message, owner, sessionId, masked);
        String runId = openedRun == null ? null : openedRun.id();
        // 审计的"这一轮开始"。与结束那条成对：只有结束的话，跑了多久、两条并发的先后都只能靠时间戳猜。
        AuditEvent.Scope auditScope = AuditEvent.Scope.of(runId, sessionId, message.principal(),
                message.channel());
        if (audit != null) {
            audit.runStarted(auditScope);
        }
        // 根 span 从"这件事"开始，而不是从这次请求开始：run 的身份比请求活得久（见 RUN_SPAN）。
        try (RunTracer.SpanScope run = tracer.open(RUN_SPAN,
                runAttributes(runId, message.principal()))) {
            try {
                ChatResponse response;
                String text;
                // 一轮的 span 包住 call() **与收尾**：seq 要先落库才拿得到，
                // 所以这段 duration 含那一次 INSERT（几十毫秒）。反过来（先关 span 再写 seq）
                // 会让属性丢掉——已 end 的 span 上 setAttribute 是静默的空操作。
                try (RunTracer.SpanScope turn = tracer.open(TURN_SPAN, Map.of())) {
                    response = promptOf(message, owner, sessionId, promptText(sessionId, masked), runId)
                            .call()
                            .chatResponse();
                    text = cappedText(response);
                    log.debug("出站回答 chatId={} runId={} text={}", message.chatId(), runId, text);
                    stampTurn(turn, closeTurn(runId, auditScope, masked, text,
                            pushTargetOf(answeredAt, message), message.notifyPolicy(),
                            tokensOf(response)), response);
                }
                return new Turn(runId, text);
            } catch (RuntimeException | Error failure) {
                run.failed(failure);
                if (audit != null) {
                    audit.runFinished(auditScope, AuditStatus.FAILED, null);
                }
                fail(runId);
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
        String address = ConversationLane.addressOf(SessionKeys.ownerOf(message.principal()),
                message.channel(), conversationOf(message));
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
        Optional<GoalCommand> goalCommand = GoalCommand.parse(parsed.text());
        if (goalCommand.isPresent()) {
            // 与 /new 那条同形：一帧正文 + 一帧空文本，调用方靠后者收尾。
            onChunk.accept(OutboundMessage.of(message.chatId(),
                    GoalCommand.execute(goalCommand.get(), goals, sessionId), null));
            onChunk.accept(OutboundMessage.of(message.chatId(), "", null));
            return;
        }
        String masked = PiiRedactor.mask(parsed.text());
        Run openedRun = openRun(message, owner, sessionId, masked);
        String runId = openedRun == null ? null : openedRun.id();
        AuditEvent.Scope auditScope = AuditEvent.Scope.of(runId, sessionId, message.principal(),
                message.channel());
        if (audit != null) {
            audit.runStarted(auditScope);
        }
        try (RunTracer.SpanScope run = tracer.open(RUN_SPAN,
                runAttributes(runId, message.principal()))) {
            StringBuilder answer = new StringBuilder();
            try (RunTracer.SpanScope turn = tracer.open(TURN_SPAN, Map.of())) {
                try {
                    promptOf(message, owner, sessionId, promptText(sessionId, masked), runId)
                            .stream()
                            .content()
                            // 每一块都带 runId：调用方可能只收到最后一块，收到中间块的也要能查到同一件事
                            .doOnNext(chunk -> {
                                log.debug("出站分块 chatId={} runId={} chunk={}", message.chatId(), runId, chunk);
                                answer.append(chunk == null ? "" : chunk);
                                onChunk.accept(OutboundMessage.chunk(message.chatId(), chunk, runId));
                            })
                            .doOnComplete(() -> onChunk.accept(OutboundMessage.of(message.chatId(), "", runId)))
                            .blockLast();
                    // 流式的 token 记 0：汇总 usage 只在最后一帧上，而它未必到得了这一层
                    // （与"不拿估算值冒充真实值"同一个口径）。于是流式这一路不计入目标用量——
                    // 这是能力不在场，不是"这一轮不花钱"（见票 04 的"没做"一节）。
                    stampTurn(turn, closeTurn(runId, auditScope, masked, answer.toString(),
                            pushTargetOf(addressOf(message), message), message.notifyPolicy(),
                            0L), null);
                } catch (RuntimeException | Error failure) {
                    // 流断在这里：两段 span 都要标失败，否则图上它是一轮"正常结束"的回答。
                    turn.failed(failure);
                    run.failed(failure);
                    if (audit != null) {
                        audit.runFinished(auditScope, AuditStatus.FAILED, null);
                    }
                    fail(runId);
                    throw failure;
                }
            }
        }
    }

    /**
     * 开这一轮（ADR-0027）。
     *
     * <p><b>只剩 start 一条路</b>：以前这里还有一个"带着 runId 接上旧事"的分支，
     * 而那条分支配着一整套 {@code resume} / {@code reopen} / {@code suspend}。
     * OpenClaw 的连续性靠 sessionKey——同一段对话自然有上下文，用户不需要携带任何标识。
     * 所以"接着做"这件事由会话负责，不由 run 负责。
     *
     * <p><b>会话键在开的时候一起写</b>：预算是按会话累计的（ADR-0025），
     * 少了这一列这条 run 就只能按自己那一行算，也就是"每轮 6 次"的旧行为。
     */
    private Run openRun(InboundMessage message, String owner, String sessionId, String masked) {
        if (runs == null) {
            return null;
        }
        return runs.start(owner, PermissionFingerprint.shortOf(message.principal()), masked,
                message.notifyPolicy(), sessionId).orElse(null);
    }

    /**
     * 交给模型的文本。
     *
     * <p><b>只有两段：活跃目标 + 这一句</b>。以前还会在中间拼一段 recap（目标 + 最近几步），
     * 那是给"run 挂起、上下文没了"准备的；上下文一直在会话里，这个前提不成立
     * （ADR-0027 决定三）。模型看到的是同一段对话的前几轮，不需要我们把它们再抄一遍。
     */
    private String promptText(String sessionId, String masked) {
        return activeGoalLine(sessionId) + masked;
    }

    /**
     * 每回合那一行"活跃目标"（ADR-0027 决定三，照 OpenClaw 的注入形态）。
     *
     * <p><b>只有 {@code active} 才注入</b>。OpenClaw 的原话是"已暂停、已阻塞、预算受限、
     * 用量受限和已完成的目标不会被注入，因此操作员的停止操作会持续生效，直到目标恢复"。
     * 这一条是"叫停能生效"的唯一保证——注入只判"有没有行"的话，用户按了暂停，
     * 下一轮模型还在往那个方向推进，而界面上写着"已暂停"。
     *
     * <p><b>折成一行</b>：目标原文可能有七行，而这里要的是一行上下文。
     * 折行只影响注入，库里存的仍是原文。
     */
    private String activeGoalLine(String sessionId) {
        if (goals == null || sessionId == null) {
            return "";
        }
        return goals.of(sessionId)
                .filter(SessionGoal::injected)
                .map(goal -> "活跃目标：" + oneLine(goal.objective(), GOAL_CHARS)
                        + " — 推进目标或更新其状态（get_goal/update_goal）。\n")
                .orElse("");
    }

    /** 把多行折成一行再截断。连续空白压成一个空格，首尾不留。 */
    private static String oneLine(String value, int max) {
        String flat = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /**
     * 记下"模型这一轮"，扣掉这一轮花掉的 token 与耗时，然后收尾（A04）。
     *
     * <p><b>这里只记模型轮，工具调用不归它记</b>：这一层看不到工具循环里调了什么——
     * {@code ToolCallLog} 是 ThreadLocal，线程池复用时读到的是上一轮的集合（它自己的
     * 注释里就写着这个坑），猜一个填上去比留空更糟。工具调用那一项由
     * {@code MeteredToolCallback} 在调用发生的地方扣额度（A04），并在同一处落一行审计（P07）——
     * 那里才真的知道"这次调用发生了"。要回答"这一轮调了哪些工具"，读 step 表里的
     * {@link StepKind#TOOL_CALLS} 行，不要读这里。
     *
     * <p><b>result_summary 是截断的摘要</b>：把它当成全文读是设计错误，
     * 完整回答在通道里；这里只需要能回答"这一步得到了什么"。
     *
     * <p><b>这一轮花掉的 token 记到目标上，不记到会话上</b>（ADR-0027 决定五）：
     * OpenClaw 的额度是 goal 的属性（{@code create_goal} 的 {@code token_budget}），
     * 用完进 {@code budget_limited}；会话本身没有"额度"这回事。
     * 记账与判超在同一条 SQL 里（见 {@code JdbcSessionGoalStore#addTokens}）。
     *
     * <p><b>这一轮的状态不由额度决定</b>：用完额度不是"这一轮失败"，
     * 它是这件事停下来了——状态在目标上，不在 run 上。
     */
    private Optional<Long> closeTurn(String runId, AuditEvent.Scope scope, String input, String answer,
                                     DeliveryTarget pushTo, NotifyPolicy policy, long tokens) {
        if (runs == null || runId == null) {
            return Optional.empty();
        }
        // 这一轮被某道门拦下并挂起了（P02）：<b>不收尾</b>。
        //
        runs.append(new StepInput(runId, StepKind.MODEL_TURN, input, null,
                summary(answer), StepStatus.DONE));
        chargeGoal(scope, tokens);
        // 全文进 run：step 上的那份是截断摘要，回答"这一步得到了什么"；
        // 查结果的人要的是完整答案，而异步场景里没有一次请求在等着接它（A05）。
        runs.finish(runId, RunStatus.DONE, answer);
        if (delivery != null && pushTo != null) {
            delivery.announce(pushTo, policy, answer);
        }
        return finishAudit(scope, AuditStatus.SUCCEEDED, null);
    }

    /** 收尾那条审计事件的账本序号。没开审计时为空，span 上就不带这一格。 */
    private Optional<Long> finishAudit(AuditEvent.Scope scope, AuditStatus status, String errorCode) {
        return audit == null ? Optional.empty() : audit.runFinished(scope, status, errorCode);
    }

    /**
     * 这一轮要不要推、推到哪儿。
     *
     * <p>同步请求（HTTP、飞书消息）的回答已经从响应里返回了，再推一次就是同一条内容出现两遍——
     * 那时返回 null。定时任务与审批续跑没有人等结果，于是推到这段对话
     * （ADR-0027 决定四：结果只回到它开始的地方，不回退到"最近用过的通道"）。
     */
    private static DeliveryTarget pushTargetOf(DeliveryTarget answeredAt, InboundMessage message) {
        return answeredAt == null ? addressOf(message) : null;
    }

    /** 这一轮来自哪段对话。没有通道名时是 null（老调用方与单测）。 */
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
     * <p><b>没有目标就不记</b>：额度是目标的属性——没有目标就没有"这件事的预算"这回事。
     * 记到一个不存在的东西上，等于把成本口径又挪回会话，而那正是本票拆掉的形状。
     *
     * <p><b>记不进去不影响回答</b>：目标存储降级（库连不上、迁移没跑过）时
     * {@code addTokens} 返回空，这一轮照常收尾。少记一笔的代价远小于把用户的话打死；
     * 那种降级由 {@code claw4j.goal.errors} 暴露。
     */
    private void chargeGoal(AuditEvent.Scope scope, long tokens) {
        if (goals == null || scope == null || scope.sessionKey() == null) {
            return;
        }
        goals.addTokens(scope.sessionKey(), tokens)
                .filter(goal -> goal.status() == GoalStatus.BUDGET_LIMITED)
                .ifPresent(goal -> log.info("目标撞上 token 预算，进入 budget_limited：session={} used={}/{}",
                        goal.sessionKey(), goal.tokensUsed(), goal.tokenBudget()));
    }

    /**
     * 撞上工具调用上限时交给用户的那句话。
     *
     * <p><b>它是止损，不是额度</b>（ADR-0027 决定五）：OpenClaw 用
     * {@code agents.defaults.timeoutSeconds}（48 小时）兜住跑飞的一轮，我们没有 per-turn 超时，
     * 所以需要一个更早的止损点。它不改任何持久状态——额度是目标的 token budget，
     * 而"这一轮绕了太多次"跟"这件事花超了"是两件事：前者要收手，后者要人决定加不加预算。
     *
     * <p><b>为什么在这里换掉那句话</b>：上限由 Spring AI 的工具循环执行
     * （{@code ToolCallLimitBehavior.THROW}），它把超限变成一代回答，话是框架的英文原话、
     * 内容是"limit exceeded"。这里是唯一既知道"这是一次回答"又知道"它撞的是哪条线"的地方，
     * 换掉比让用户去读一句框架内部的话更合适。
     *
     * <p>判定看的是 {@code finishReason} 这个公开常量，不是文本匹配：
     * 拿话术去认"这是不是超限"会在框架改一次措辞之后静默失效。
     */
    private static String cappedText(ChatResponse response) {
        String text = textOf(response);
        boolean capped = response != null && response.getResult() != null
                && response.getResult().getMetadata() != null
                && ToolCallLimitExceededException.FINISH_REASON
                .equals(response.getResult().getMetadata().getFinishReason());
        return capped ? TOOL_CALL_CAPPED : text;
    }

    /** 回答文本。没有 generation 时是 null，而不是造一个空串去冒充"模型说了话"。 */
    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    /**
     * 这一轮花掉的 token。拿不到就是 0——不拿估算值冒充真实值
     * （与 {@code MeteredChatModel} 同一个口径）。
     */
    private static long tokensOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return 0L;
        }
        Integer total = response.getMetadata().getUsage().getTotalTokens();
        return total == null ? 0L : total.longValue();
    }

    /** 收尾成失败。<b>不吞异常</b>：run 只负责记下"这件事没成"，怎么向上报告仍由调用方决定。 */
    private void fail(String runId) {
        if (runs == null || runId == null) {
            return;
        }
        runs.finish(runId, RunStatus.FAILED);
    }

    /** 摘要长度。取头部而不是"尽量多留"：读的人要看的是"发生了什么"，不是重读一遍回答。 */
    private static String summary(String text) {
        if (text == null) {
            return null;
        }
        String oneLine = text.strip();
        return oneLine.length() <= SUMMARY_CHARS ? oneLine : oneLine.substring(0, SUMMARY_CHARS) + "…";
    }

    /**
     * 把 Principal 与会话 id 交给 Advisor 链：走 Advisor 的上下文，不走 ThreadLocal。
     *
     * <p>上下文会跟着 {@code ChatClientRequest} 走完工具循环的每一轮（工具循环
     * 每轮都把 {@code context} 原样带下去），也跟着 Reactor 的订阅走；
     * ThreadLocal 在"流式 + 换线程"的组合下会掉，而掉身份的失败姿势是放行。
     *
     * <p>会话 id 由 {@link SessionKeys} 从 Principal 派生，不是 {@link InboundMessage#chatId()}：
     * 后者只是通道侧的寻址键，拿它当会话主键会让"换通道"变成"换会话"。
     * 这里设的是 Advisor 上下文里的参数，链上没有记忆 Advisor 时它安静地被忽略。
     *
     * @param runId 这一轮的 run。<b>两处都要放</b>：工具上下文给工具里的写操作（计划，A03），
     *              Advisor 上下文给鉴权那一层（审批要挂起 run，P02）——
     *              只在工具上下文里放的话，Advisor 拦下工具调用时手里没有 runId，
     *              这一等就只能挂在库里没有落点的位置上
     */
    /**
     * 把这一轮的结论写到 {@code model.turn} 上（P14）：审计行序号、花掉的 token、哪个模型答的。
     *
     * <p><b>三个值都"没有就不写"</b>：seq 落不下去（存储降级）时写 0 会让人以为第一行就是它；
     * token 拿不到时写 0 与"这轮没花钱"分不开；模型名同理。**空属性是诚实的，
     * 补一个看起来合理的默认值才是**——后者的代价是某天有人按这个数字判断成本。
     *
     * @param response 可为 null（流式那一侧拿不到汇总 usage）
     */
    private static void stampTurn(RunTracer.SpanScope turn, Optional<Long> seq, ChatResponse response) {
        // 这一格现在装的是审计账本序号（ADR-0027）：工具与模型轮落在同一个账本上，
        // 于是"哪一段 span 对应账本哪一行"只有一个答案。以前它装的是 step 序号，工具与模型轮分属两处。
        seq.ifPresent(value -> turn.attribute(ATTR_STEP_SEQ, Long.toString(value)));
        long tokens = tokensOf(response);
        if (tokens > 0) {
            turn.attribute(ATTR_TOKENS, Long.toString(tokens));
        }
        String model = modelOf(response);
        if (model != null && !model.isBlank()) {
            turn.attribute(ATTR_MODEL, model);
        }
    }

    /** 响应里报的模型名。不是我们配的路由名——路由在 {@code MeteredChatModel} 那一层，这里看不到。 */
    private static String modelOf(ChatResponse response) {
        return response == null || response.getMetadata() == null
                ? null : response.getMetadata().getModel();
    }

    /**
     * 根 span 的属性：哪一轮、谁的（指纹）。
     *
     * <p><b>没有 runId 时不写这一格</b>：没装 run 存储的部署里它确实是 null，
     * 而写一个空串会让"这件事"与"空"在图上长得一样。
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

    private ChatClient.ChatClientRequestSpec promptOf(InboundMessage message, String owner, String sessionId,
                                                      String masked, String runId) {
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
        // 子 Agent 要用**父这个身份**继续鉴权——委派不提权，
        // 而"子那边拿不到身份"的失败姿势必须是拒绝（见 SubagentDelegation）。
        toolContext.put(AuthorizationAdvisor.PRINCIPAL_CONTEXT_KEY, principal);
        // runId 同理：计划工具在工具内部，它要知道这一步落在哪件事上。
        // 没有 run 时干脆不放这个键——工具那边"读不到就是这一轮没有 run"，
        // 比放一个空串让它去猜清楚。
        if (runId != null) {
            toolContext.put(ContextKeys.RUN_ID, runId);
        }
        // 会话 id 也进工具上下文：recall_session 只查本段对话，它得知道"这一段"是哪一段。
        // 键用框架的 ChatMemory.CONVERSATION_ID，与它自己的会话工具（SessionEventTools）
        // 是同一个字符串——哪天换成框架那个工具，键名不必再改一次。
        // 放这里而不是让工具自己按 principal 推：会话键里还有通道与 chatId，工具推不出来，
        // 让它去猜就会退化成"这个人的所有会话"，而那正是私聊内容被带进群聊的那条路。
        toolContext.put(ChatMemory.CONVERSATION_ID, sessionId);
        // 通道与地址也进工具上下文（ADR-0027）：审批要写"这条请求来自哪段对话"，
        // 定时任务要写"结果推回哪里"。以前这两处拿 runId 去投递表反查，
        // 而地址本来就是这一轮的属性，不该绕一次库、更不该靠一张要下线的表。
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
                    // 鉴权那一层也要知道这是哪件事（P02）：审批没批过时它要把这件事挂起，
                    // 而"挂起谁"这个答案只在 runId 上。
                    if (runId != null) {
                        spec.param(ContextKeys.RUN_ID, runId);
                    }
                });
    }
}
