package io.github.claw4j.agent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.advisor.PiiRedactor;
import io.github.claw4j.agent.observability.MeteredToolCallback;
import io.github.claw4j.agent.observability.RunTracer;
import io.github.claw4j.agent.run.Budget;
import io.github.claw4j.agent.run.DeliveryTarget;
import io.github.claw4j.agent.run.Run;
import io.github.claw4j.agent.run.RunDelivery;
import io.github.claw4j.agent.run.RunBudget;
import io.github.claw4j.agent.run.RunStatus;
import io.github.claw4j.agent.run.RunStore;
import io.github.claw4j.agent.run.RunLauncher;
import io.github.claw4j.agent.run.RunResumer;
import io.github.claw4j.agent.run.Step;
import io.github.claw4j.agent.run.StepInput;
import io.github.claw4j.agent.run.StepKind;
import io.github.claw4j.agent.run.StepStatus;
import io.github.claw4j.agent.session.SessionCompactor;
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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

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
public class ChatClientAgentPort implements AgentPort, RunResumer, RunLauncher {

    private static final Logger log = LoggerFactory.getLogger(ChatClientAgentPort.class);

    /** step 摘要的字符上限。 */
    private static final int SUMMARY_CHARS = 500;

    /** 接上时给模型回看几步。 */
    private static final int RECAP_STEPS = 5;

    /** recap 里计划最多列几步。计划一行一步，比 step 摘要紧凑，所以给得宽一些。 */
    private static final int RECAP_PLAN_STEPS = 10;

    /** recap 里每一步与目标的字符上限。 */
    private static final int RECAP_CHARS = 120;

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
     * 这一次 run 的预算。可以为 null——没有 run 存储就没有额度可记（A04）。
     * 它与 {@link #runs} 同进同出：记账与限流分家的话，会出现"没记账却被拦住"的拒绝。
     */
    private final RunBudget budget;

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

    public ChatClientAgentPort(ChatClient chatClient) {
        this(chatClient, null, null, null, null);
    }

    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor) {
        this(chatClient, compactor, null, null, null);
    }

    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor, RunStore runs) {
        this(chatClient, compactor, runs, null, null);
    }

    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor, RunStore runs,
                               RunBudget budget) {
        this(chatClient, compactor, runs, budget, null);
    }

    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor, RunStore runs,
                               RunBudget budget, RunDelivery delivery) {
        this(chatClient, compactor, runs, budget, delivery, RunTracer.NONE);
    }

    /**
     * @param tracer 为 null 时按空实现处理：装配里它走 {@code ObjectProvider}，
     *               没开观测的部署拿不到 bean，而"少一个 bean"不该让门都装不上
     */
    public ChatClientAgentPort(ChatClient chatClient, SessionCompactor compactor, RunStore runs,
                               RunBudget budget, RunDelivery delivery, RunTracer tracer) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
        this.compactor = compactor;
        this.runs = runs;
        this.budget = budget;
        this.delivery = delivery;
        this.tracer = tracer == null ? RunTracer.NONE : tracer;
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
        Turn turn = answer(message, addressOf(message));
        return OutboundMessage.of(message.chatId(), turn.text(), turn.runId());
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
        return Optional.ofNullable(answer(message, null).text());
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
        String owner = SessionKeys.ownerOf(message.principal());
        // 对话键与归属键是两把：owner 管"这是谁的东西"，sessionId 管"这是哪一段话"。
        // 群聊与私聊必须是两段——同一份权限下，听众可以完全不同（ADR-0025）。
        String sessionId = SessionKeys.sessionIdOf(owner, message.channel(), message.chatId());
        String masked = PiiRedactor.mask(message.text());
        Opened opened = openRun(message, owner, sessionId, masked);
        String runId = opened == null ? null : opened.run().id();
        // 根 span 从"这件事"开始，而不是从这次请求开始：run 的身份比请求活得久（见 RUN_SPAN）。
        try (RunTracer.SpanScope run = tracer.open(RUN_SPAN,
                runAttributes(runId, message.principal(), opened))) {
            // 进门先看额度：上一轮已经把额度烧完的 run，这一轮不该再调一次模型——
            // 调了也只是再花一份钱去换一个"还是得停"的结论。
            Optional<Budget> overBudget = exhaustedSpent(runId);
            if (overBudget.isPresent()) {
                // 停下来这件事本身要留痕：图上"这里少了一轮模型"与"模型一轮就答完了"
                // 长得一样，而这句属性是唯一分得开它们的东西。
                run.attribute(MeteredToolCallback.ATTR_OUTCOME, "budget_exhausted");
                return new Turn(runId, stopForBudget(message, runId, overBudget.get()).text());
            }
            long startedAt = System.nanoTime();
            try {
                ChatResponse response;
                String text;
                // 一轮的 span 包住 call() **与收尾**：seq 要先落库才拿得到，
                // 所以这段 duration 含那一次 INSERT（几十毫秒）。反过来（先关 span 再写 seq）
                // 会让属性丢掉——已 end 的 span 上 setAttribute 是静默的空操作。
                try (RunTracer.SpanScope turn = tracer.open(TURN_SPAN, Map.of())) {
                    response = promptOf(message.principal(), owner, sessionId, promptText(opened, masked), runId)
                            .call()
                            .chatResponse();
                    text = textOf(response);
                    log.debug("出站回答 chatId={} runId={} text={}", message.chatId(), runId, text);
                    stampTurn(turn, closeTurn(runId, masked, text, answeredAt,
                            tokensOf(response), elapsedMillis(startedAt)), response);
                }
                return new Turn(runId, text);
            } catch (RuntimeException | Error failure) {
                run.failed(failure);
                fail(runId);
                throw failure;
            }
        }
    }

    @Override
    public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(onChunk, "onChunk");

        String owner = SessionKeys.ownerOf(message.principal());
        String sessionId = SessionKeys.sessionIdOf(owner, message.channel(), message.chatId());
        String masked = PiiRedactor.mask(message.text());
        Opened opened = openRun(message, owner, sessionId, masked);
        String runId = opened == null ? null : opened.run().id();
        try (RunTracer.SpanScope run = tracer.open(RUN_SPAN,
                runAttributes(runId, message.principal(), opened))) {
            Optional<Budget> overBudget = exhaustedSpent(runId);
            if (overBudget.isPresent()) {
                run.attribute(MeteredToolCallback.ATTR_OUTCOME, "budget_exhausted");
                onChunk.accept(stopForBudget(message, runId, overBudget.get()));
                onChunk.accept(OutboundMessage.of(message.chatId(), "", runId));
                return;
            }
            StringBuilder answer = new StringBuilder();
            long startedAt = System.nanoTime();
            try (RunTracer.SpanScope turn = tracer.open(TURN_SPAN, Map.of())) {
                try {
                    promptOf(message.principal(), owner, sessionId, promptText(opened, masked), runId)
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
                    // （与"不拿估算值冒充真实值"同一个口径）。耗时照记——它不需要模型报。
                    stampTurn(turn, closeTurn(runId, masked, answer.toString(), addressOf(message),
                            0L, elapsedMillis(startedAt)), null);
                } catch (RuntimeException | Error failure) {
                    // 流断在这里：两段 span 都要标失败，否则图上它是一轮"正常结束"的回答。
                    turn.failed(failure);
                    run.failed(failure);
                    fail(runId);
                    throw failure;
                }
            }
        }
    }

    /**
     * 审批放行之后由服务端接着做（P02）。
     *
     * <p><b>用发起人的身份，不用审批人的</b>：run 上存着进门时的身份快照。
     * 拿审批人的身份续跑等于"我批了你就能用我的权限干"——那不是审批，是授权变更。
     *
     * <p><b>交给模型的是 recap 加一句结论，不是从头重说</b>：这正是挂起-续跑这套东西
     * 存在的理由。只给一句"批了"的话，模型得自己从会话记忆里翻回三十分钟前的上下文，
     * 而记忆里那一段可能已经被压过。
     *
     * <p><b>拒绝也要用这一条</b>：理由回灌给模型，"换个做法"才有依据。只把 run 标成失败、
     * 不告诉模型为什么的话，用户下一次说"继续"时它会把同一个请求原样再发一遍。
     *
     * <p>读不到身份快照就<b>不续跑</b>：猜一个身份去跑比停下来更糟。
     */
    @Override
    public Optional<String> resume(String runId, String note) {
        if (runs == null || runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        Optional<Run> reopened = runs.reopen(runId);
        if (reopened.isEmpty()) {
            log.warn("run {} 不在等待状态，审批放行也不会把它拉起来", runId);
            return Optional.empty();
        }
        Optional<Principal> principal = runs.principalOf(runId);
        if (principal.isEmpty()) {
            log.warn("run {} 读不到发起人快照，这一轮不续跑", runId);
            return Optional.empty();
        }
        Optional<Budget> overBudget = exhaustedSpent(runId);
        if (overBudget.isPresent()) {
            runs.append(new StepInput(runId, StepKind.MODEL_TURN, note, null,
                    "预算用尽，审批放行后没有调用模型：" + budget.reason(overBudget.get()),
                    StepStatus.FAILED));
            runs.finish(runId, RunStatus.BUDGET_EXHAUSTED);
            return Optional.of(budget.outcomeText(overBudget.get(), runId));
        }
        Run run = reopened.get();
        // 这条路上没有入站消息，算不出会话键，只能回读：续跑的回答要落回
        // "当初提问的那段对话"。老数据没有归属就退到无通道占位段——
        // 至少不会把审批结果写进另一个人的另一段对话里。
        String sessionId = runs.sessionOf(runId)
                .filter(id -> !id.isBlank())
                .orElseGet(() -> SessionKeys.sessionIdOf(run.owner(), null, null));
        String prompt = recap(run, runs.stepsOf(runId)) + "[审批有结论] " + note;
        long startedAt = System.nanoTime();
        // 续跑也是一次"执行这件事"：单独一条 trace，但根还是 run。不包的话这一轮的
        // model.turn 会成为一条没有根的 trace——图上表现为"有个模型轮不知道属于哪件事"。
        try (RunTracer.SpanScope span = tracer.open(RUN_SPAN,
                runAttributes(runId, principal.get(), null))) {
            try (RunTracer.SpanScope turn = tracer.open(TURN_SPAN, Map.of())) {
                try {
                    ChatResponse response = promptOf(principal.get(), run.owner(), sessionId, prompt, runId)
                            .call()
                            .chatResponse();
                    String text = textOf(response);
                    // 这一轮没有"就地回答"的地址：没有人正盯着一个请求等结果，
                    // 于是结果按通知策略送到登记过的每一个地方（A05）。
                    stampTurn(turn, closeTurn(runId, "[审批] " + note, text, null,
                            tokensOf(response), elapsedMillis(startedAt)), response);
                    return Optional.ofNullable(text);
                } catch (RuntimeException | Error failure) {
                    turn.failed(failure);
                    span.failed(failure);
                    fail(runId);
                    log.error("审批放行后 run {} 的续跑失败", runId, failure);
                    return Optional.empty();
                }
            }
        }
    }

    /** 这一轮落在哪件事上，以及它是不是接上来的。 */
    private record Opened(Run run, boolean resumed) {
    }

    /**
     * 开一个 run，或者接上一个已有的（A02）。返回 null 表示"这一轮没有 run"
     * （没装存储，或没记下来）——那是降级，不是错误，所以不抛。
     *
     * <p><b>接不上的时候另开一个，而不是报错</b>：runId 是调用方带来的一个提示，
     * 不是这个人的义务。它可能是过期的、抄错的、或者那件事正在等审批——
     * 三种情况下用户的"继续"都得有回答，而不是一个 400。
     * 而"另开一个"绝不意味着接上别人的：那是 {@code RunStore#resume} 用
     * {@code owner} 挡住的，这里连重试都不做。
     */
    private Opened openRun(InboundMessage message, String owner, String sessionId, String masked) {
        if (runs == null) {
            return null;
        }
        Opened opened = null;
        String asked = message.runId();
        if (asked != null && !asked.isBlank()) {
            Optional<Run> resumed = runs.resume(asked, owner);
            if (resumed.isPresent()) {
                opened = new Opened(resumed.get(), true);
            } else {
                // 三种接不上：没有这个 run / 不是你的 / 它正在等审批。
                // 不分别查一次再决定——多一次往返，而结论与动作都一样。
                log.warn("runId={} 接不上（不存在、不属于 {}，或正在等审批），这一轮另开一个 run",
                        asked, owner);
            }
        }
        if (opened == null) {
            opened = runs.start(owner, PermissionFingerprint.shortOf(message.principal()), masked,
                            message.notifyPolicy())
                    .map(run -> new Opened(run, false))
                    .orElse(null);
        }
        // 记下发起人（P02）：批准之后服务端要用他的身份续跑。用审批人的身份续跑等于提权，
        // 而只凭权限指纹反推不出角色与可访问表——所以进门这一下就要整份存下来。
        if (opened != null) {
            runs.attachPrincipal(opened.run().id(), message.principal());
            // 会话键在开 run 之后补写：预算是按会话判的（ADR-0025），
            // 没有它这条 run 就只能按自己那一行算——也就是"每轮 6 次"的旧行为。
            runs.attachSession(opened.run().id(), sessionId);
        }
        // 收件地址在进门时登记，接上旧事时也登记：后者正是"换一个通道接着做"的那一次，
        // 不登记它，结果就只能回到第一次提问的那个通道（A05）。
        if (opened != null && delivery != null) {
            delivery.register(opened.run().id(), message.channel(), message.chatId());
        }
        return opened;
    }

    /**
     * 交给模型的文本。接上一件旧事时前面加一段"做到哪儿了"。
     *
     * <p>不加它，"继续"在跨通道、或者会话被压过之后就是一句没有上下文的话——
     * 模型只能从头猜，而它的表现恰恰是"看起来答得挺好，其实是把第一轮重做了一遍"。
     * 会话记忆还在，但它记的是说过的话，不是"这件事做到第几步"。
     *
     * <p><b>计划是另一段，单独列</b>（A03）：计划 step 记的是"打算怎么干、还剩什么没做"，
     * 与"已经发生了什么"是两种读法。混在"最近几步"里的话，
     * "还剩三步没做"会和"已完成三步"挤在同一段里，模型只能靠数行数去分辨。
     *
     * <p>只回看最近几步、每步截断：这段 recap 的用途是让模型知道下一步做什么，
     * 不是让它把历史重读一遍——全量塞进去会在长 run 上先撞上下文上限。
     */
    private String promptText(Opened opened, String masked) {
        if (opened == null || !opened.resumed()) {
            return masked;
        }
        return recap(opened.run(), runs.stepsOf(opened.run().id())) + masked;
    }

    private static String recap(Run run, List<Step> steps) {
        List<Step> plan = steps.stream().filter(step -> step.kind() == StepKind.PLAN).toList();
        List<Step> happened = steps.stream().filter(step -> step.kind() != StepKind.PLAN).toList();
        StringBuilder text = new StringBuilder("[接着做这件事 ")
                .append(run.id(), 0, Math.min(8, run.id().length()))
                .append("]\n目标：")
                .append(clip(run.goal(), RECAP_CHARS))
                .append('\n');
        if (!plan.isEmpty()) {
            text.append(planBlock(plan));
        }
        if (happened.isEmpty()) {
            text.append("还没有任何一步落下来。\n");
        } else {
            List<Step> recent = happened.size() <= RECAP_STEPS
                    ? happened : happened.subList(happened.size() - RECAP_STEPS, happened.size());
            text.append("已发生 ").append(happened.size()).append(" 步，最近的是：\n");
            for (Step step : recent) {
                text.append("第 ").append(step.seq()).append(" 步：").append(clip(step.input(), RECAP_CHARS))
                        .append(" → ").append(clip(step.resultSummary(), RECAP_CHARS)).append('\n');
            }
        }
        return text.append("（以上是这件事已经做完的部分，直接接着做下一步，不要重做。）\n").toString();
    }

    /**
     * 计划那一段：这是"还剩什么没做"唯一的来源（A03）。
     *
     * <p>会话被压过之后，模型手里的上下文里没有计划，只有这张表里还有。
     * 不把它放进 recap，"继续"就会把已经做过的那一步重做一遍，
     * 而<b>重做一遍看起来跟"接着做"一样正常</b>——这是这条最要紧的理由。
     *
     * <p>序号是计划内序号（清单里的第几个），不是 {@link Step#seq()}：
     * 计划被重写过之后它的 seq 从当时那一步接着往下走，
     * 模型手里的"第 2 步"指的是清单里的第二个。
     */
    private static String planBlock(List<Step> plan) {
        long pending = plan.stream().filter(step -> step.status() == StepStatus.PENDING).count();
        StringBuilder text = new StringBuilder("计划（共 ").append(plan.size())
                .append(" 步，还有 ").append(pending).append(" 步没做）：\n");
        for (int i = 0; i < plan.size() && i < RECAP_PLAN_STEPS; i++) {
            Step step = plan.get(i);
            text.append("第 ").append(i + 1).append(" 步 [").append(step.status().label()).append("] ")
                    .append(clip(step.input(), RECAP_CHARS)).append('\n');
        }
        if (plan.size() > RECAP_PLAN_STEPS) {
            text.append("（计划只列前 ").append(RECAP_PLAN_STEPS).append(" 步）\n");
        }
        return text.toString();
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "(无)";
        }
        String oneLine = value.strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
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
     * <p><b>收尾状态由额度决定，不是一律 done</b>：这一轮刚好把额度烧完时，
     * 这件事停在 {@link RunStatus#BUDGET_EXHAUSTED}——它与 done 的下一步完全不同
     * （追加预算继续 vs 没有了），混成一个状态的话没人知道该重试还是该换做法。
     */
    private Optional<Step> closeTurn(String runId, String input, String answer, DeliveryTarget answeredAt,
                                     long tokens, long millis) {
        if (runs == null || runId == null) {
            return Optional.empty();
        }
        // 这一轮被某道门拦下并挂起了（P02）：<b>不收尾</b>。
        //
        // 收尾会把 run 写回 done，而"批准之后接着做"整个依赖于那个 suspended——
        // 覆盖掉之后，批准会落到一件已经做完的事上，reopen 直接失败，
        // 用户点完按钮什么都不会发生，也没有任何报错。
        //
        // 也不记 model_turn：这一轮实际上没跑完，记一步"已做"会让 recap 里的进度
        // 比事实多一步（模型下一轮就会跳过那件没做完的事）。
        if (isSuspended(runId)) {
            log.debug("run {} 这一轮停在等审批，不收尾", runId);
            return Optional.empty();
        }
        Optional<Step> written = runs.append(new StepInput(runId, StepKind.MODEL_TURN, input, null,
                summary(answer), StepStatus.DONE));
        if (budget != null) {
            budget.chargeTurn(runId, tokens, millis);
        }
        Optional<Budget> spent = budget == null ? Optional.empty() : budget.spent(runId);
        if (spent.isPresent() && budget.exhausted(spent.get())) {
            runs.finish(runId, RunStatus.BUDGET_EXHAUSTED);
            // 这一步是落下来的：span 上仍要带上它的序号——"这一轮把额度烧完了"正是
            // 最想按 (run_id, seq) 回库核对的那一轮。
            return written;
        }
        // 全文进 run：step 上的那份是截断摘要，回答"这一步得到了什么"；
        // 查结果的人要的是完整答案，而异步场景里没有一次请求在等着接它（A05）。
        runs.finish(runId, RunStatus.DONE, answer);
        if (delivery != null) {
            delivery.announce(runId, RunStatus.DONE, answer, answeredAt);
        }
        return written;
    }

    /** 这一轮里有没有人把这件事实停下来了。读不到 run 时按"没有"处理（照常收尾）。 */
    private boolean isSuspended(String runId) {
        Optional<Run> current = runs.find(runId);
        return current.isPresent() && current.get().status() == RunStatus.SUSPENDED;
    }

    /** 这一轮已经就地回答过的地址。没有通道名时是 null（异步生产没有就地回答）。 */
    private static DeliveryTarget addressOf(InboundMessage message) {
        String channel = message.channel();
        if (channel == null || channel.isBlank()) {
            return null;
        }
        return new DeliveryTarget(channel, message.chatId());
    }

    /**
     * 进门那一问：这一件还有额度吗。
     *
     * <p>读不到用量时（没有 run、存储降级）返回空——那种情况下放行，
     * 因为管不住预算的失败姿势应该是"少记一笔"，不是"把活停了"。
     */
    private Optional<Budget> exhaustedSpent(String runId) {
        if (runs == null || runId == null || budget == null) {
            return Optional.empty();
        }
        return budget.spent(runId).filter(budget::exhausted);
    }

    /**
     * 超预算的结局：写明停在哪、为什么停，并且不调模型。
     *
     * <p><b>这一步也会落库</b>：否则"这件事为什么停在第 3 轮"在库里查不到答案，
     * 而那正是审计（P07）最常问的一句。状态是
     * {@link StepStatus#FAILED}——这一轮没有发生，不是发生了但做得不好。
     */
    private OutboundMessage stopForBudget(InboundMessage message, String runId, Budget spent) {
        String text = budget.outcomeText(spent, runId);
        runs.append(new StepInput(runId, StepKind.MODEL_TURN, PiiRedactor.mask(message.text()), null,
                "预算用尽，没有调用模型：" + budget.reason(spent), StepStatus.FAILED));
        runs.finish(runId, RunStatus.BUDGET_EXHAUSTED);
        log.debug("run {} 的预算用尽，这一轮没有调用模型", runId);
        return OutboundMessage.of(message.chatId(), text, runId);
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

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
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
    private static void stampTurn(RunTracer.SpanScope turn, Optional<Step> step, ChatResponse response) {
        step.ifPresent(written -> turn.attribute(ATTR_STEP_SEQ, Integer.toString(written.seq())));
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
     * 根 span 的属性：哪件事、谁的（指纹）、是不是接上来的。
     *
     * <p><b>没有 runId 时不写这一格</b>：没装 run 存储的部署里它确实是 null，
     * 而写一个空串会让"这件事"与"空"在图上长得一样。
     */
    private static Map<String, String> runAttributes(String runId, Principal principal, Opened opened) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (runId != null) {
            attributes.put(ATTR_RUN_ID, runId);
        }
        if (principal != null) {
            attributes.put(ATTR_OWNER, PermissionFingerprint.shortOf(principal));
        }
        if (opened != null && opened.resumed()) {
            attributes.put(ATTR_RESUMED, "true");
        }
        return attributes;
    }

    private ChatClient.ChatClientRequestSpec promptOf(Principal principal, String owner, String sessionId,
                                                      String masked, String runId) {
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
        return chatClient.prompt(masked)
                .toolContext(toolContext)
                .advisors(spec -> {
                    spec.param(AuthorizationAdvisor.PRINCIPAL_CONTEXT_KEY, principal)
                            .param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId)
                            // 外键用归属键而不是裸 userId：跨会话召回走 user_id 精确匹配，
                            // 传裸 userId 的话"换角色即换会话"在召回那条路上等于没做（见 SessionKeys）。
                            .param(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, owner);
                    // 鉴权那一层也要知道这是哪件事（P02）：审批没批过时它要把这件事挂起，
                    // 而"挂起谁"这个答案只在 runId 上。
                    if (runId != null) {
                        spec.param(ContextKeys.RUN_ID, runId);
                    }
                });
    }
}
