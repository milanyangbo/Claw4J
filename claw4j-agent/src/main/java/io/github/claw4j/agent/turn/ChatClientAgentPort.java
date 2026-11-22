package io.github.claw4j.agent.turn;

import io.github.claw4j.agent.approval.Elevations;
import io.github.claw4j.agent.audit.AuditErrorCode;
import io.github.claw4j.agent.audit.AuditEvent;
import io.github.claw4j.agent.audit.AuditStatus;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.agent.channel.ConversationDelivery;
import io.github.claw4j.agent.channel.DeliveryTarget;
import io.github.claw4j.agent.goal.SessionGoalStore;
import io.github.claw4j.agent.observability.SpanTracer;
import io.github.claw4j.agent.persona.AgentProperties;
import io.github.claw4j.agent.session.SessionCompactor;
import io.github.claw4j.agent.session.SessionEntries;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.capability.tool.ToolCatalog;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.untrusted.OutboundText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;

import java.time.Clock;
import java.time.Duration;
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
 *
 * <p><b>这一个类只编排时序</b>：拿锁、开 span、调模型、收尾。四件相邻的事各有住处——
 * 会话键在 {@link SessionAddress}，斜杠命令在 {@link SlashCommands}，请求拼装在
 * {@link PromptComposer}，记账与投递在 {@link TurnRecorder}。它们的共同点只是「一轮里都要用到」，
 * 而不是「属于门面」：此前它们挤在同一个文件里，读一遍主路径要跨过一百多行观测细节。
 */
public class ChatClientAgentPort implements AgentPort, TaskLauncher {

    private static final Logger log = LoggerFactory.getLogger(ChatClientAgentPort.class);

    /**
     * 撞上工具调用上限时回给用户的那一句。
     *
     * <p>只说「停了」不够：用户会以为是自己那句话有问题。要说清是这个动作被反复调了太多次，
     * 以及下一步能做什么（把要求说细一点）。
     */
    public static final String TOOL_CALL_CAPPED =
            "这一步做了太多次，我先停下。请把要求说得更具体一些（查哪张表、哪段时间），"
                    + "我再试一次。";

    /** 会话定位（两个键的形状）。 */
    private final SessionAddress address;

    /** 斜杠命令：不进模型、当场办的那条路。 */
    private final SlashCommands commands;

    /** 这一轮的请求怎么拼。 */
    private final PromptComposer composer;

    /** 记账与收尾：span、审计、额度、投递。 */
    private final TurnRecorder recorder;

    /**
     * 一段对话的串行化（ADR-0026 决定二）。可以为 null：没有它时两条并发消息各跑各的，也就是串行化
     * 之前的行为。这不是降级更好，是让只装配了 ChatClient 的调用方（例子、测试）不必凭空造一个
     * 调度器。
     *
     * <p>它必须包在最外层，不能包进请求拼装里：套在里面时 run 已经开出来了，审计里会多出
     * 一条并行的事，「这段对话同时只有一个 run」就不成立。
     */
    private final ConversationLane lane;

    /**
     * 只连 ChatClient 的门：无压缩、无投递、无观测、无审计。
     *
     * <p>给测试与最小例子用。它等价于 {@code builder(chatClient).build()}，留着是因为「一个
     * ChatClient 就能起一个门」这件事本身值得一行说出来。
     *
     * @param chatClient 对话客户端，必填
     */
    public ChatClientAgentPort(ChatClient chatClient) {
        this(builder(chatClient));
    }

    /** 由 builder 装好的门；依赖都已拆到四个协作者里。 */
    private ChatClientAgentPort(Builder builder) {
        ChatClient chatClient = Objects.requireNonNull(builder.chatClient, "chatClient");
        this.address = new SessionAddress(builder.pointers);
        this.commands = new SlashCommands(address, builder.goals, builder.elevations);
        this.composer = new PromptComposer(chatClient, builder.catalog, builder.providerKey,
                builder.compactor, builder.elevations, builder.goals, builder.progressCards, builder.intents,
                builder.turnTimeout, builder.clock);
        this.recorder = new TurnRecorder(builder.tracer, builder.audit, builder.delivery, builder.goals);
        this.lane = builder.lane;
    }

    /**
     * 开始装一个门。所有依赖都有默认值（缺席 = 这项能力不在场），因此最小调用只要一个 ChatClient。
     *
     * @param chatClient 对话客户端，必填
     * @return builder
     */
    public static Builder builder(ChatClient chatClient) {
        return new Builder(chatClient);
    }

    /**
     * 门的装配器。
     *
     * <p>从十三个位置参数的重载改成一个 builder：原先每加一个依赖就多一个构造器（实测堆到 13 个），
     * 而调用方要点着参数数位置才知道第四个 {@code null} 是压缩器还是投递。名字在这里是免费的。
     */
    public static final class Builder {

        private final ChatClient chatClient;
        private SessionCompactor compactor;
        private ConversationDelivery delivery;
        private SpanTracer tracer;
        private SessionEntries pointers;
        private ConversationLane lane;
        private ToolCallAudit audit;
        private SessionGoalStore goals;
        private io.github.claw4j.agent.progress.ProgressCards progressCards;
        private io.github.claw4j.agent.intent.StandingIntents intents;
        private ToolCatalog catalog;
        private String providerKey;
        private Elevations elevations;
        private Duration turnTimeout = AgentProperties.DEFAULT_TURN_TIMEOUT;
        private Clock clock = Clock.systemUTC();

        private Builder(ChatClient chatClient) {
            this.chatClient = chatClient;
        }

        /**
         * @param value 压缩器，可以为 null（没有会话存储就没有可压的）
         * @return this
         */
        public Builder compactor(SessionCompactor value) {
            this.compactor = value;
            return this;
        }

        /**
         * @param value 结果投递，可以为 null（没有推送实现时不推）
         * @return this
         */
        public Builder delivery(ConversationDelivery value) {
            this.delivery = value;
            return this;
        }

        /**
         * @param value 埋点入口，可以为 null（用 {@link SpanTracer#NONE}）
         * @return this
         */
        public Builder tracer(SpanTracer value) {
            this.tracer = value;
            return this;
        }

        /**
         * @param value 会话指针，可以为 null（没有"段"这回事）
         * @return this
         */
        public Builder pointers(SessionEntries value) {
            this.pointers = value;
            return this;
        }

        /**
         * @param value 对话串行化，可以为 null（不串行）
         * @return this
         */
        public Builder lane(ConversationLane value) {
            this.lane = value;
            return this;
        }

        /**
         * @param value 审计规则层，可以为 null（这个部署没开审计）
         * @return this
         */
        public Builder audit(ToolCallAudit value) {
            this.audit = value;
            return this;
        }

        /**
         * @param value 目标存储，可以为 null（这个部署没开目标存储）
         * @return this
         */
        public Builder goals(SessionGoalStore value) {
            this.goals = value;
            return this;
        }

        /**
         * @param value 进度卡存储，可以为 null（这个部署没开会话存储：工具清单原样给）
         * @return this
         */
        public Builder progressCards(io.github.claw4j.agent.progress.ProgressCards value) {
            this.progressCards = value;
            return this;
        }

        /**
         * @param value 意图存储，可以为 null（这个部署不做匹配）
         * @return this
         */
        public Builder intents(io.github.claw4j.agent.intent.StandingIntents value) {
            this.intents = value;
            return this;
        }

        /**
         * @param value 工具清单，可以为 null（按"只有全局层"解析）
         * @return this
         */
        public Builder catalog(ToolCatalog value) {
            this.catalog = value;
            return this;
        }

        /**
         * @param value 当前供应商名，可以为 null（"按模型"那层不生效）
         * @return this
         */
        public Builder providerKey(String value) {
            this.providerKey = value;
            return this;
        }

        /**
         * @param value 提权状态，可以为 null（这一族命令没开）
         * @return this
         */
        public Builder elevations(Elevations value) {
            this.elevations = value;
            return this;
        }

        /**
         * @param value 一轮最多跑多久；{@link Duration#ZERO} 表示不设墙钟
         * @return this
         */
        public Builder turnTimeout(Duration value) {
            this.turnTimeout = value == null ? AgentProperties.DEFAULT_TURN_TIMEOUT : value;
            return this;
        }

        /**
         * @param value 算截止时刻的钟，为 null 时用系统钟
         * @return this
         */
        public Builder clock(Clock value) {
            this.clock = value == null ? Clock.systemUTC() : value;
            return this;
        }

        /**
         * @return 装好的门
         */
        public ChatClientAgentPort build() {
            return new ChatClientAgentPort(this);
        }
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
        Turn turn = inLane(message, () -> answer(message, TurnRecorder.addressOf(message)));
        // 出站剥离：包装头、数据块标签、纯文本工具调用块都不该让用户读到（票 02）。
        // 存进会话记忆的仍是原文——模型下一轮需要知道那段内容从哪来。
        return OutboundMessage.of(message.chatId(), OutboundText.clean(turn.text()), turn.runId());
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

    @Override
    public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(onChunk, "onChunk");
        if (lane == null) {
            streamInLane(message, onChunk);
            return;
        }
        String key = ConversationLane.addressOf(
                address.conversationKeyOf(message, SessionKeys.ownerOf(message.principal())));
        try {
            lane.run(key, () -> {
                streamInLane(message, onChunk);
                return null;
            });
        } catch (LaneBusyException busy) {
            // 与 reply 同一条口径：说话，不沉默。最后那一帧空文本是"这一路结束"的约定，
            // 没有它，调用方的流永远不会完成。
            //
            // 正文必须走 chunk（finalChunk=false）：带上 finalChunk 的那一帧在通道那边被当成
            // 结束信号、正文直接丢掉，用户只会看到连接断了、一个字没有，而且 done 会来两次。
            log.warn("这段对话还在处理上一句，这一路流式没等到：chatId={}", message.chatId());
            onChunk.accept(OutboundMessage.chunk(message.chatId(), ConversationLane.BUSY_REPLY));
            onChunk.accept(OutboundMessage.of(message.chatId(), "", null));
        }
    }

    /** 这一轮的结果：编号与回答。两个都可能为空——命令类回执既没有编号，也没有调模型。 */
    private record Turn(String runId, String text) {
    }

    /**
     * 跑一轮：认命令、开 run、调模型、记账与收尾。
     *
     * @param message 入站消息
     * @param answeredAt 这一轮已经就地回答过的地址；null 表示没有人在等结果（定时任务、服务端续跑），
     *     于是按策略推给每一个登记过的地址
     * @return 这一轮的回答；命令类回执没有 {@code runId}
     */
    private Turn answer(InboundMessage message, DeliveryTarget answeredAt) {
        String owner = SessionKeys.ownerOf(message.principal());
        // 目标挂在会话节点上（不含段），与含段的 sessionId 不是同一个字符串。
        String conversationKey = address.conversationKeyOf(message, owner);
        // /new、/goal、/elevated 这一族与"跑一轮"同层：认出来就当场办、回一句话，不进模型也不开 run
        // （OpenClaw：纯命令消息绕过队列和模型）。先判命令再算 sessionId：/new 的换段副作用就在
        // 命令那一层发生，两处各算一次会让"换了没有"取决于走了哪条分支。
        SlashCommands.Parsed parsed = commands.parse(message, owner, conversationKey);
        if (parsed.reply().isPresent()) {
            return new Turn(null, parsed.reply().get());
        }
        // 对话键与归属键是两把：owner 管"这是谁的东西"，sessionId 管"这是哪一段话"。
        // 群聊与私聊必须是两段——同一份权限下，听众可以完全不同（ADR-0025）。
        // 段由指针给（ADR-0026）：跨天、空闲、或者用户发了 /new 就换一个。
        String sessionId = address.sessionIdOf(message, owner, parsed.reset());
        // 这一轮的编号（ADR-0027）：本地生成，不落任何表。它只在三处用——审计的两条事件、
        // trace 的一段 span、返回给调用方的回执。
        // OpenClaw 的 runId 也是这个角色：agent RPC 返回它，agent.wait 可以等它，
        // 但没有任何"按 runId 取回一件事"的接口，因为一轮答完它就作废了。
        String runId = UUID.randomUUID().toString();
        // 审计的"这一轮开始"。与结束那条成对：只有结束的话，跑了多久、两条并发的先后都只能靠时间戳猜。
        AuditEvent.Scope auditScope = AuditEvent.Scope.of(runId, sessionId, message.principal(),
                message.channel());
        recorder.runStarted(auditScope);
        // 根 span 从"这一轮"开始，而不是从这次请求开始：模型轮与工具都发生在入池之后（见 RUN_SPAN）。
        try (SpanTracer.SpanScope run = recorder.tracer().open(TurnRecorder.RUN_SPAN,
                TurnRecorder.runAttributes(runId, message.principal()))) {
            try {
                ChatResponse response;
                String text;
                // 一轮的 span 包住 call() 与收尾，所以它量的是"这一轮从开跑到答完"的整段时长，
                // 含收尾那一次审计 INSERT（几十毫秒）。span 上不写账本行号：
                // 与审计的关联键只有 claw4j.run.id 一个。
                try (SpanTracer.SpanScope turn = recorder.tracer().open(TurnRecorder.TURN_SPAN, Map.of())) {
                    response = composer.compose(message, owner, sessionId, conversationKey,
                                    parsed.text(), runId)
                            .call()
                            .chatResponse();
                    text = cappedText(response);
                    // 日志敢打全文，是因为脱敏已经在 Advisor 链上做完；哪天有人在链路之外打日志，
                    // 这里会立刻出现原始值。
                    log.debug("出站回答 chatId={} runId={} text={}", message.chatId(), runId, text);
                    recorder.closeTurn(auditScope, conversationKey, text,
                            TurnRecorder.pushTargetOf(answeredAt, message), message.notifyPolicy(),
                            TurnRecorder.tokensOf(response));
                    TurnRecorder.stampTurn(turn, response);
                }
                return new Turn(runId, text);
            } catch (RuntimeException | Error failure) {
                run.failed(failure);
                recorder.runFinished(auditScope, AuditStatus.FAILED, AuditErrorCode.RUN_FAILED);
                throw failure;
            }
        }
    }

    /** 真正的流式那一轮。锁由 {@link #stream} 拿——放在这里就晚了，run 已经开出来了。 */
    private void streamInLane(InboundMessage message, Consumer<OutboundMessage> onChunk) {
        String owner = SessionKeys.ownerOf(message.principal());
        String conversationKey = address.conversationKeyOf(message, owner);
        SlashCommands.Parsed parsed = commands.parse(message, owner, conversationKey);
        if (parsed.reply().isPresent()) {
            // 与"忙"那条同形：一帧正文 + 一帧空文本，调用方靠后者收尾。
            // 正文那一帧不能带 finalChunk：通道看到它只发 done、不发正文（见 AgentPort#stream）。
            onChunk.accept(OutboundMessage.chunk(message.chatId(), parsed.reply().get()));
            onChunk.accept(OutboundMessage.of(message.chatId(), "", null));
            return;
        }
        String sessionId = address.sessionIdOf(message, owner, parsed.reset());
        String runId = UUID.randomUUID().toString();
        AuditEvent.Scope auditScope = AuditEvent.Scope.of(runId, sessionId, message.principal(),
                message.channel());
        recorder.runStarted(auditScope);
        try (SpanTracer.SpanScope run = recorder.tracer().open(TurnRecorder.RUN_SPAN,
                TurnRecorder.runAttributes(runId, message.principal()))) {
            StringBuilder answer = new StringBuilder();
            // 流式的出站剥离必须有状态：标记可能被切在两片之间。三种收尾（正常/异常/超时）
            // 都要 flush，否则最后半句永远发不出去（票 02）。
            OutboundText.StreamStripper stripper = new OutboundText.StreamStripper();
            try (SpanTracer.SpanScope turn = recorder.tracer().open(TurnRecorder.TURN_SPAN, Map.of())) {
                try {
                    composer.compose(message, owner, sessionId, conversationKey, parsed.text(), runId)
                            .stream()
                            .content()
                            // 每一块都带 runId：调用方可能只收到最后一块，收到中间块的也要能认出同一批
                            .doOnNext(chunk -> {
                                log.debug("出站分块 chatId={} runId={} chunk={}", message.chatId(), runId, chunk);
                                answer.append(chunk == null ? "" : chunk);
                                String out = stripper.feed(chunk);
                                if (!out.isEmpty()) {
                                    onChunk.accept(OutboundMessage.chunk(message.chatId(), out, runId));
                                }
                            })
                            .doOnComplete(() -> {
                                String tail = stripper.flush();
                                if (!tail.isEmpty()) {
                                    onChunk.accept(OutboundMessage.chunk(message.chatId(), tail, runId));
                                }
                                onChunk.accept(OutboundMessage.of(message.chatId(), "", runId));
                            })
                            .blockLast();
                    // 流式这一路手上没有 ChatResponse（汇总 usage 在最后一帧，未必到得了），
                    // 所以 token 传 null 而不是 0：0 会被读成「这一轮没花钱」，
                    // 而这是「没有数」。代价是流式的用量不计入目标预算（见票 04 的「没做」一节）。
                    recorder.closeTurn(auditScope, conversationKey, answer.toString(),
                            TurnRecorder.pushTargetOf(TurnRecorder.addressOf(message), message),
                            message.notifyPolicy(), null);
                } catch (RuntimeException | Error failure) {
                    // 断流也要把暂存的那截吐出来，否则用户看到"话说到一半没了"。
                    // 发不出去就算了：原始失败更重要，别被它盖掉。
                    try {
                        String tail = stripper.flush();
                        if (!tail.isEmpty()) {
                            onChunk.accept(OutboundMessage.chunk(message.chatId(), tail, runId));
                        }
                    } catch (RuntimeException ignored) {
                        log.debug("断流时暂存的那截没发出去：chatId={} runId={}", message.chatId(), runId);
                    }
                    // 流断在这里：两段 span 都要标失败，否则图上它是一轮"正常结束"的回答。
                    turn.failed(failure);
                    run.failed(failure);
                    recorder.runFinished(auditScope, AuditStatus.FAILED, AuditErrorCode.RUN_FAILED);
                    throw failure;
                }
            }
        }
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
        String key = ConversationLane.addressOf(
                address.conversationKeyOf(message, SessionKeys.ownerOf(message.principal())));
        try {
            return lane.run(key, work);
        } catch (LaneBusyException busy) {
            log.warn("这段对话还在处理上一句，这一句没等到：chatId={}", message.chatId());
            return new Turn(null, ConversationLane.BUSY_REPLY);
        }
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
}
