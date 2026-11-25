package io.github.claw4j.agent.turn;

import io.github.claw4j.agent.approval.Elevations;
import io.github.claw4j.agent.advisor.PiiRedactor;
import io.github.claw4j.agent.audit.AuditEvent;
import io.github.claw4j.agent.intent.IntentBlock;
import io.github.claw4j.agent.intent.IntentMatcher;
import io.github.claw4j.agent.intent.StandingIntent;
import io.github.claw4j.agent.intent.StandingIntents;
import io.github.claw4j.agent.progress.ProgressCardCallback;
import io.github.claw4j.agent.progress.ProgressCards;
import io.github.claw4j.capability.tool.ToolCatalog;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Elevation;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPolicyContext;
import io.github.claw4j.domain.untrusted.InboundText;
import org.springaicommunity.agent.advisors.InterruptAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 拼出这一轮真正发给模型的请求：文本、工具清单、上下文、墙钟。
 *
 * <p>抽出来是因为门面里它占了一百多行，而它与「跑一轮的时序」（lane、审计、收尾）没有关系：
 * 给它一段文本和几个键，它给一个待执行的请求。
 *
 * <p>三件事必须一起做，分开做会漏：<b>入站脱敏</b>（会话记忆在 before 就把用户消息写进库，
 * 出站脱敏改不到它）、<b>按这一轮的身份筛工具</b>（provider 展开时拿不到这一轮是谁）、
 * <b>把身份与会话键放进两条上下文</b>（工具读 ToolContext，审批与鉴权读 Advisor 上下文）。
 */
final class PromptComposer {

    private static final Logger log = LoggerFactory.getLogger(PromptComposer.class);

    /**
     * 命中就记一次触发，并把提醒拼进这一轮。
     *
     * <p>失败不阻塞回复（ADR-0036 决定四）：库抖一下的代价只能是"这次没提醒"，不能是
     * "这条消息没人理"。所以整段包在 try 里，异常只记一行日志。
     *
     * @param principal 这一轮的身份
     * @param message   入站消息，取频道做作用域
     * @param text      用户这一句（已脱敏）
     * @return 有界提醒块；没有命中或降级时为空串
     */
    private String reminderOf(Principal principal, InboundMessage message, String text) {
        if (intents == null) {
            return "";
        }
        try {
            Instant now = clock.instant();
            List<StandingIntent> candidates = intents.eligible(principal.userId(),
                    message.channel(), principal.userId(), now, IntentMatcher.MAX_CANDIDATES);
            List<StandingIntent> matched = IntentMatcher.match(candidates, text);
            if (matched.isEmpty()) {
                return "";
            }
            List<StandingIntent> fired = new ArrayList<>(matched.size());
            for (StandingIntent intent : matched) {
                // 记账在库里做（状态、冷却、预算都在那条 update 的 where 里），
                // 记上了才算这次真的提醒了。
                if (intents.fire(intent.id(), now)) {
                    fired.add(intent);
                }
            }
            return IntentBlock.of(fired);
        } catch (RuntimeException degraded) {
            log.warn("意图匹配降级：这一轮不提醒", degraded);
            return "";
        }
    }

    private final ChatClient chatClient;

    /**
     * 这一轮该给模型看哪些工具。
     *
     * <p>它必须在这里而不是靠 {@code ChatClient} 的默认 provider：provider 展开时<b>拿不到</b>
     * "这一轮谁在问、用的哪个模型"，而按发送者与按模型那几层正需要它（ADR-0030 裁决二）。
     * 没有它时清单按"只有全局层"解析——安全但不精确，所以装配时一定会传。
     */
    private final ToolCatalog catalog;

    /** 当前模型的供应商名，用于"按模型"那一层。降级到备模型时清单不重算，见 ADR 缺口。 */
    private final String providerKey;

    /** 这一段会话的提权档（票 06）；没有时为 {@code null}，等同于"这一族命令没开"。 */
    private final Elevations elevations;

    /**
     * 进度卡（票 01）。为 null 表示没装配会话存储：工具清单原样给。
     *
     * <p>它在这里而不是在 {@code ToolCatalog} 里：卡属于会话，而 catalog 属于能力层，
     * 能力层不认识"会话"这件事。
     */
    private final ProgressCards progressCards;

    /**
     * 事件条件意图（票 02）。为 null 表示没装配会话存储：不做匹配。
     *
     * <p>匹配放在这一层而不是 Advisor：Advisor 的 before 拿到的是已经被记忆拼过历史的 prompt，
     * 而意图该匹配的是<b>用户这一句</b>——历史里出现过的词不该让提醒反复触发。
     */
    private final StandingIntents intents;

    /**
     * 一轮最多跑多久；{@link Duration#ZERO} 表示不设墙钟。
     *
     * <p>它兜的是次数管不住的那种：单次工具调用卡住时，{@code tool-call-limit} 还没数到，
     * 这一轮就永远不回来。判定在库的 {@code InterruptAdvisor} 里做（每次工具往返之后重查一遍），
     * 这里只负责算截止时刻。
     */
    private final Duration turnTimeout;

    /** 算截止时刻的钟。测试注入固定值，跑起来是系统钟。 */
    private final Clock clock;

    /** 这一轮是哪个 agent 在跑（ADR-0037 票 01）。只进工具上下文，只给审计读。 */
    private final String agentId;

    /**
     * @param chatClient 对话客户端，必填
     * @param catalog 工具清单，可以为 null（按"只有全局层"解析）
     * @param providerKey 当前供应商名，可以为 null
     * @param elevations 提权状态，可以为 null
     * @param progressCards 进度卡存储，可以为 null（工具清单原样给）
     * @param intents 意图存储，可以为 null（不做匹配）
     * @param turnTimeout 一轮最多跑多久，可以为 null（取默认值）
     * @param clock 算截止时刻的钟，可以为 null（取系统钟）
     * @param agentId 这一轮是哪个 agent 在跑，只用于审计归属；为 null 或空白时落成 unknown
     */
    PromptComposer(ChatClient chatClient, ToolCatalog catalog, String providerKey,
                   Elevations elevations, ProgressCards progressCards, StandingIntents intents,
                   Duration turnTimeout, Clock clock, String agentId) {
        this.chatClient = chatClient;
        this.catalog = catalog;
        this.providerKey = providerKey;
        this.elevations = elevations;
        this.progressCards = progressCards;
        this.intents = intents;
        this.turnTimeout = turnTimeout == null
                ? io.github.claw4j.agent.persona.AgentProperties.DEFAULT_TURN_TIMEOUT : turnTimeout;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.agentId = agentId == null || agentId.isBlank()
                ? AuditEvent.Scope.UNKNOWN_AGENT : agentId;
    }

    /**
     * 拼出这一轮的请求：脱敏后的文本、工具上下文、Advisor 上下文、墙钟。
     *
     * <p>身份与会话 id 走 Advisor 的上下文，不走 ThreadLocal：上下文会跟着
     * {@code ChatClientRequest} 走完工具循环的每一轮（每轮都把 {@code context} 原样带下去），也跟着
     * Reactor 的订阅走；ThreadLocal 在「流式 + 换线程」的组合下会掉，而掉身份的失败姿势是放行。
     *
     * <p>会话 id 由 {@link io.github.claw4j.agent.session.SessionKeys} 从 Principal 派生，不是
     * {@link InboundMessage#chatId()}：后者只是通道侧的寻址键，拿它当会话主键会让「换通道」变成
     * 「换会话」。链上没有记忆 Advisor 时这些参数被安静地忽略。
     *
     * <p>{@code executionId} 两处都要放：工具上下文给工具里的写操作（计划，A03），Advisor 上下文给鉴权
     * 那一层（审批要挂起 run，P02）。只在工具上下文里放的话，Advisor 拦下工具调用时手里没有
     * {@code executionId}，这一等就只能挂在库里没有落点的位置上。
     *
     * @param message 入站消息
     * @param owner 归属键
     * @param sessionId 本轮的会话 id
     * @param conversationKey 会话节点键（不含段）
     * @param text 剥掉斜杠命令之后的原文（尚未脱敏）
     * @param executionId 这一轮的编号，可为 null
     * @return 待执行的请求
     */
    ChatClient.ChatClientRequestSpec compose(InboundMessage message, String owner, String sessionId,
                                             String conversationKey, String text, String executionId) {
        Principal principal = message.principal();
        // 归属键与对话键都由调用方算好再传进来：一轮请求里 run 的 owner、会话 id
        // 都要用，各算一遍的话，口径一改就可能只改到一半，而表现是"会话记得住、run 查不到"。
        // 压缩交给 SessionMemoryAdvisor 的 compactionTrigger（ADR-0026 修订）：它在 after 阶段
        // 判一次、本轮没请求工具调用才压。这里不再先压一遍——两边都开会一轮压两次。
        // 入站就脱敏（ADR-0011 里真模型测试抓到的那个缺陷）：
        // 会话记忆是在 Advisor 链的 before 阶段最早那一步把用户消息写进库的，
        // 而出站脱敏发生在 after——它改得到模型的回答，改不到已经落库的那条用户消息。
        // 于是"出站是掩码、会话表里躺着明文"这种更持久的泄露就出现了，
        // 而且没有任何一条假模型的测试会发现它：假模型时代的用例从不往用户消息里放号码。
        //
        // 代价要记账：模型从此看不到用户写的原始号码。当前没有"按手机号查订单"这类工具，
        // 掩码不影响任何能力；哪天有了，得给那个工具开一条白名单，而不是把入站脱敏撤掉。
        String masked = InboundText.neutralizeSystemTags(PiiRedactor.mask(text));
        Map<String, Object> toolContext = new HashMap<>();
        // 身份也放进工具上下文：委派（Subagent）发生在工具内部，
        // 那里拿不到 Advisor 上下文，只能从 ToolContext 里取。
        // 子 Agent 要用父这个身份继续鉴权——委派不提权，
        // 而"子那边拿不到身份"的失败姿势必须是拒绝（见 SubagentDelegation）。
        toolContext.put(ContextKeys.PRINCIPAL, principal);
        // 这一轮的编号同理：工具在工具内部，它要知道"我这一轮属于哪一次回答"。
        // 不放这个键时，工具那边读不到就是空——比放一个空串让它去猜清楚。
        if (executionId != null) {
            toolContext.put(ContextKeys.EXECUTION_ID, executionId);
        }
        // 哪个 agent 在跑也进工具上下文（ADR-0037 票 01）：工具事件的审计归属从这里读。
        // 与 executionId 同一个理由——工具回调拿不到 Advisor 上下文，只能从 ToolContext 取。
        toolContext.put(ContextKeys.AGENT_ID, agentId);
        // 会话 id 也进工具上下文：recall_session 只查本段对话，它得知道"这一段"是哪一段。
        // 键用框架的 ChatMemory.CONVERSATION_ID，与它自己的会话工具（SessionEventTools）
        // 是同一个字符串——哪天换成框架那个工具，键名不必再改一次。
        // 放这里而不是让工具自己按 principal 推：会话键里还有通道与 chatId，工具推不出来，
        // 让它去猜就会退化成"这个人的所有会话"，而那正是私聊内容被带进群聊的那条路。
        toolContext.put(ChatMemory.CONVERSATION_ID, sessionId);
        // 会话节点键（不含段）也进工具上下文：目标挂在节点上，与记忆的按段隔离是两件事。
        // 让工具自己去 sessionId 里切段是不行的——chatId 里可以有冒号（cron: 就是）。
        toolContext.put(ContextKeys.CONVERSATION, conversationKey);
        // 提权档也进工具上下文（票 06）：exec 的判定在工具那一层，它要知道"这一段刚 /elevated 过"。
        // 状态在内存里、按会话键存；没开这一族命令时写 off，等于没提权。
        toolContext.put(ContextKeys.ELEVATION,
                (elevations == null ? Elevation.OFF : elevations.level(conversationKey)).id());
        // 通道与地址也进工具上下文（ADR-0027）：审批要写"这条请求来自哪段对话"，
        // 定时任务要写"结果推回哪里"。地址是这一轮的属性——不该绕一次库，也不该靠另一张表登记。
        if (message.channel() != null && !message.channel().isBlank()) {
            toolContext.put(ContextKeys.CHANNEL, message.channel());
        }
        if (message.chatId() != null && !message.chatId().isBlank()) {
            toolContext.put(ContextKeys.CHAT_REF, message.chatId());
        }
        ChatClient.ChatClientRequestSpec request =
                chatClient.prompt(reminderOf(principal, message, masked)
                        + masked).toolContext(toolContext);
        if (catalog != null) {
            // 工具按这一轮的上下文现筛（ADR-0030 裁决二）：按模型 / 按发送者 / 子 Agent 角色那几层
            // 要靠"这一轮是谁、用的哪个模型"，而上下文只有这里拿得到——provider 展开时不带上下文。
            request = request.toolCallbacks(ProgressCardCallback.wrap(
                    catalog.toolsFor(
                            ToolPolicyContext.of(message.channel(), principal.userId())
                                    .withProvider(providerKey)),
                    progressCards));
        }
        return withTurnDeadline(request)
                .advisors(spec -> {
                    spec.param(ContextKeys.PRINCIPAL, principal)
                            .param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId)
                            // 外键用归属键而不是裸 userId：跨会话召回走 user_id 精确匹配，
                            // 传裸 userId 的话"换角色即换会话"在召回那条路上等于没做（见 SessionKeys）。
                            .param(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, owner);
                    // 审批那一层读的是这份上下文，不是 ToolContext（README 的那条接缝）：
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
                    if (executionId != null) {
                        spec.param(ContextKeys.EXECUTION_ID, executionId);
                    }
                });
    }

    /**
     * 给这一轮挂上墙钟。
     *
     * <p><b>每轮一个新的实例</b>，不挂在 {@code defaultAdvisors} 上：库的 signal 是构建期固定的
     * {@code BooleanSupplier}，而截止时刻是每一轮算的。单例只能表达「全部署停」。
     *
     * <p>order 用库的默认值（工具循环内层），于是每次工具往返之后、下一次模型请求之前都会重查一遍。
     * 这一条已核过框架：{@code DefaultAroundAdvisorChain.Builder#pushAll} 末尾的 {@code reOrder()}
     * 会把 default 与 per-request 的 advisor 一起按 order 排序，两者一视同仁。
     *
     * @param request 这一轮的请求
     * @return 挂上墙钟的请求；不设墙钟时原样返回
     */
    private ChatClient.ChatClientRequestSpec withTurnDeadline(ChatClient.ChatClientRequestSpec request) {
        if (turnTimeout.isZero() || turnTimeout.isNegative()) {
            return request;
        }
        Instant deadline = clock.instant().plus(turnTimeout);
        return request.advisors(InterruptAdvisor.builder()
                .interruptSignal(() -> !clock.instant().isBefore(deadline))
                .build());
    }

}
