package io.github.claw4j.agent.advisor;

import io.github.claw4j.agent.approval.ApprovalGate;
import io.github.claw4j.agent.approval.CallFingerprint;
import io.github.claw4j.agent.audit.AuditEvent;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.port.ApprovalLedger;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 鉴权 Advisor：一次工具调用能不能发生由 Principal 决定，不由模型决定（ADR-0007）。
 *
 * <p>摆位是这个 Advisor 的全部要点。工具循环（{@code ToolSearchToolCallingAdvisor}，它继承
 * {@code ToolCallingAdvisor}）把"再问一次模型"实现成
 * "自己递归调用链上后续的 Advisor"，所以排在它**之后**的 Advisor 会在
 * <b>每一次工具迭代</b>上都被走一遍；排在它之前的只在整通对话里走一次。
 * 链是按 order 升序排的，于是"在循环内部"= order 大于 {@code ToolSearchToolCallingAdvisor.DEFAULT_ORDER}。
 *
 * <p>判定发生在模型返回之后、工具执行之前：这一轮模型说"我要调 querySales"，
 * 本 Advisor 先看 Principal 有没有这个权限，没有就把这一轮换成一个明确的拒绝，
 * 并且**不把工具调用请求往下传**——工具循环因此看不到工具调用，
 * 工具一次都不会被执行。注意这与"让工具跑完再过滤结果"有本质区别：
 * 后者在执行那一刻越权已经发生了。
 *
 * <p>为什么不直接用 {@code BaseAdvisor} 的默认流式实现：它只在
 * {@code AdvisorUtils.onFinishReason()} 命中的那一块上调 {@code after()}，
 * 而真实模型把工具调用分成很多块发（名字在第一块、参数是后面几块），
 * 带 finish reason 的收尾块里通常**已经没有工具调用**了。所以在流式上只能逐块看，
 * 看到越权就当场掐断上游并把这一轮换成拒绝。
 *
 * <p><b>同一处还有第二道门：审批</b>（P01、P02）。鉴权通过之后，权限上标了
 * {@link io.github.claw4j.domain.principal.Approval#REQUIRED} 的动作要再问一句
 * {@link ApprovalGate}：没有一条同人、同工具、<b>同参数</b>的批准就换成一句"要先审批"，
 * 同时把这件事停在这一点（step awaiting、run suspended）。
 * 两道门在这里同一个位置、同一个机制（都不把工具调用往下传），因为它们的约束是同一条——
 * 判定必须在<b>工具执行之前</b>发生；分成两处实现的话，早晚有一处漏掉流式路径。
 * 但两句话术不合并：一个要改角色配置，一个要找人点按钮。
 *
 * <p><b>为什么审批要等参数收全才判</b>：指纹是"工具名 + 规范化参数"，
 * 而流式下工具调用的参数是分片到达的（第一片通常只有名字）。拿半截参数算出来的指纹
 * 每收到一片就变一次，用它去问账本等于永远问不到那条批准。所以流式路径上按调用 id
 * 累积参数，直到它能解析成一个完整的 JSON 对象才判——那一刻一定经过某一帧，
 * 所以不会漏判。授权那一道仍然在名字出现的第一帧就判，它不需要参数。
 *
 * <p><b>它也是审计的那半个接缝</b>（P07）：被拒与等审批都在这里落一行（{@link ToolCallAudit}）——
 * 这两件事的判断只发生在这里，工具根本没被执行，{@code MeteredToolCallback} 看不到它们。
 * 放行的那一半不在这里落库：兑现批准只是"允许执行"，真正跑没跑要等工具回调说了算。
 */
public class AuthorizationAdvisor implements CallAdvisor, StreamAdvisor {

    /**
     * Principal 在 Advisor 上下文里的键。由 {@code ChatClientAgentPort} 放入，本 Advisor 取出。
     *
     * <p>取值来自 domain 的 {@link ContextKeys#PRINCIPAL}：工具回调里读的是同一个键
     * （委派与脚本执行都发生在工具内部，那里没有 Advisor 上下文），两边各写一份常量的话，
     * 漂移的表现是"工具侧读到 null、一律按没身份拒绝"。
     */
    public static final String PRINCIPAL_CONTEXT_KEY = ContextKeys.PRINCIPAL;

    /** 拒绝话术的统一前缀。测试靠它区分"明确的拒绝"与"查出来是空的"。 */
    public static final String DENIAL_PREFIX = "没有权限";

    /**
     * 拦在审批上的那句话的前缀。**与 {@link #DENIAL_PREFIX} 分开**：这两件事的下一步动作不同——
     * "没有权限"要找管理员改角色配置，"等你批"要找审批人点一下。合成一句话，
     * 收到它的人只会去问同一件事，而能解决它的人一直没被找到。
     */
    public static final String APPROVAL_PREFIX = "这个动作要先审批";

    /** 被拒（越权）与等批各记一个 outcome：一条是"谁能干什么"的证据，一条是"谁在等谁"的证据。 */
    private static final String OUTCOME_DENIED = "denied";

    private static final String OUTCOME_NEEDS_APPROVAL = "needs_approval";

    /**
     * 参数摘要的字符上限。
     *
     * <p>存摘要而不是原文（ADR-0016）：第三方 MCP 的参数里可能是凭据。而完全没有摘要的话，
     * 审批人就是在一个看不见内容的按钮上盖章——那比不留摘要更糟。
     */
    private static final int ARGS_SUMMARY_CHARS = 240;

    /**
     * 排在工具循环内部：比 {@code ToolSearchToolCallingAdvisor.DEFAULT_ORDER} 大，
     * 于是每次迭代都过一遍；比模型调用 Advisor（{@code LOWEST_PRECEDENCE}）小，
     * 于是还有机会在工具执行之前拦下来。
     */
    public static final int ORDER = ToolSearchToolCallingAdvisor.DEFAULT_ORDER + 100;

    private final List<ToolPermissionRegistry> registries;

    /**
     * 审批门（P01、P02）：鉴权通过之后，标了 {@code REQUIRED} 的动作要在这里问一句、
     * 没有就登记一条并把这件事实停在这一步。
     *
     * <p>默认 {@link ApprovalGate#denyAll()}——没有审批设施时一律"没有批准"，
     * 于是标了要审批的动作会被拦，而不是悄悄退化成普通动作。
     */
    private final ApprovalGate gate;

    /**
     * 审计（P07）：被拒与等审批都落一行，兑现过的批准也在这里登记一下，
     * 让执行那一下能把"这次调用是哪次批准的"写进去。
     *
     * <p>可以为 null（没装配 run 存储的部署）：审计缺席不影响判定——
     * 拒还是拒，等还是等，只是没人在事后查得到。
     */
    private final ToolCallAudit audit;

    /**
     * 拒绝次数往哪记。默认 {@link Metrics#globalRegistry}：没装 actuator 的调用方
     * （只把 {@code claw4j-agent} 当库使）也能拿到计数，装了则由容器注入真正的 registry。
     * 不用 {@code null} 表示"不记"——那会让下面每个用法点都多一个判空分支。
     */
    private final MeterRegistry registry;

    public AuthorizationAdvisor(List<ToolPermissionRegistry> registries) {
        this(registries, ApprovalGate.denyAll(), Metrics.globalRegistry);
    }

    public AuthorizationAdvisor(List<ToolPermissionRegistry> registries, MeterRegistry registry) {
        this(registries, ApprovalGate.denyAll(), registry);
    }

    public AuthorizationAdvisor(List<ToolPermissionRegistry> registries, ApprovalGate gate) {
        this(registries, gate, Metrics.globalRegistry);
    }

    public AuthorizationAdvisor(List<ToolPermissionRegistry> registries, ApprovalGate gate,
                                MeterRegistry registry) {
        this(registries, gate, registry, null);
    }

    /**
     * @param audit 审计落点（P07）。null 表示没装配 run 存储：判定照常，只是事后查不到这一次
     */
    public AuthorizationAdvisor(List<ToolPermissionRegistry> registries, ApprovalGate gate,
                                MeterRegistry registry, ToolCallAudit audit) {
        this.registries = List.copyOf(Objects.requireNonNull(registries, "registries"));
        this.gate = Objects.requireNonNull(gate, "gate");
        this.registry = registry == null ? Metrics.globalRegistry : registry;
        this.audit = audit;
    }

    @Override
    public String getName() {
        return "authorizationAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(chain, "chain");
        ChatClientResponse response = chain.nextCall(request);
        return denyIfNecessary(request.context(), response);
    }

    /**
     * 流式路径：逐块看，看到越权的工具调用就发一条拒绝并掐断上游。
     *
     * <p>不能攒到最后再判断：攒起来的话，越权那一轮的内容已经吐出去了，
     * 而工具循环拿的是聚合后的响应，它照样会去执行工具。
     *
     * <p><b>每次订阅一套自己的状态</b>（{@code Flux.defer}）：参数累积与"已判定"两个集合
     * 是这次订阅的进度。放在外层的话，第二次订阅会带着第一次的结论跑——
     * 表现是第二次从头到尾一次都不判，全部放行。
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(chain, "chain");
        Map<String, Object> context = request.context();
        return Flux.defer(() -> {
            Map<String, String> partialArgs = new HashMap<>();
            Set<String> settled = new HashSet<>();
            return chain.nextStream(request).handle((chunk, sink) -> {
                Optional<Denial> denial = denialFor(context, requestedCalls(chunk.chatResponse()),
                        partialArgs, settled);
                if (denial.isPresent()) {
                    onDenial(denial.get(), context);
                    sink.next(replacedWith(chunk, denial.get().text()));
                    sink.complete();
                    return;
                }
                sink.next(chunk);
            });
        });
    }

    /** 同步路径：模型这一轮要调的工具逐个过一遍，有一个越权就把整轮换成拒绝。 */
    private ChatClientResponse denyIfNecessary(Map<String, Object> context, ChatClientResponse response) {
        // 同步路径上参数是一次给全的，没有"收一半"这种状态。
        Optional<Denial> denial = denialFor(context, requestedCalls(response.chatResponse()),
                new HashMap<>(), new HashSet<>());
        if (denial.isEmpty()) {
            return response;
        }
        onDenial(denial.get(), context);
        return replacedWith(response, denial.get().text());
    }

    /**
     * 一条拦下来的调用，带上是被哪个工具触发的——指标要按工具分，
     * 光有"拒绝了几次"查不到该改哪条权限。
     *
     * @param outcome    记在 {@code claw4j.tool.calls} 上的那一档：越权（{@code denied}）
     *                   与等审批（{@code needs_approval}）是两条完全不同的处置线索
     * @param args       模型给的参数原文（流式下可能是收了一半的）。审计只拿它算指纹与摘要，
     *                   不落库
     * @param approvalId 等审批那一条的编号；越权时为 null。审计要它才答得出"这次调用是哪次批准的"
     */
    private record Denial(String tool, String text, String outcome, String args, String approvalId) {
    }

    /**
     * 模型这一轮提出的一次调用。<b>id 用于流式下的参数累积</b>：同一个调用会分好几帧到达，
     * 每帧只带一小段参数，靠 id 才认得出它们说的是同一件事。
     */
    private record Called(String id, String name, String arguments) {
    }

    /**
     * 两道门依次过。
     *
     * <p><b>先全部过鉴权，再过审批</b>，而不是对每个调用"先鉴权再审批"：
     * 一场轮次里只要有一个越权，整轮就该以越权结束——那句话说的是"你得先改配置"，
     * 比同时抛出"顺便还有一件事在等你批"清楚得多。混着报会让人以为批了就能过。
     *
     * @param partialArgs 流式下的参数累积（按调用 id）。同步路径传一个空 map，用不上
     * @param settled     流式下已经判过的调用 id。同一帧里参数再长一点不该重判——
     *                    重判会重复兑现批准，而批准是一次性的
     */
    private Optional<Denial> denialFor(Map<String, Object> context, List<Called> calls,
                                       Map<String, String> partialArgs, Set<String> settled) {
        if (calls.isEmpty()) {
            return Optional.empty();
        }
        Principal principal = principalOf(context);
        if (principal == null) {
            Called first = calls.getFirst();
            return Optional.of(new Denial(first.name(),
                    DENIAL_PREFIX + "：本次请求没有携带身份，工具调用一律不放行。", OUTCOME_DENIED,
                    first.arguments(), null));
        }
        for (Called call : calls) {
            Optional<Denial> denial = authorizationDenial(principal, call);
            if (denial.isPresent()) {
                return denial;
            }
        }
        for (Called call : calls) {
            Optional<Denial> denial = approvalDenial(context, principal, call, partialArgs, settled);
            if (denial.isPresent()) {
                return denial;
            }
        }
        return Optional.empty();
    }

    /** 第一道门：这个身份能不能执行这个工具要求的动作、碰它要求的表。 */
    private Optional<Denial> authorizationDenial(Principal principal, Called call) {
        String toolName = call.name();
        Optional<ToolPermission> permission = permissionFor(toolName);
        if (permission.isEmpty()) {
            return Optional.of(new Denial(toolName, DENIAL_PREFIX + "：工具 " + toolName
                    + " 未登记权限（没人评估过它会碰到什么），按越权处理。", OUTCOME_DENIED,
                    call.arguments(), null));
        }
        ToolPermission required = permission.get();
        if (!principal.canUse(required)) {
            return Optional.of(new Denial(toolName, denialText(principal, toolName, required),
                    OUTCOME_DENIED, call.arguments(), null));
        }
        return Optional.empty();
    }

    /**
     * 第二道门：这一次具体要干的这件事，有没有人点头。
     *
     * <p>顺序不能反（先问"能不能干"再问"有没有人批"）：反过来的话，一个本来没权限的动作
     * 会以"等审批"的形式出现——等多久也不会通过。
     *
     * <p><b>参数收不全就不判</b>：返回空（当作"还没到该判的时候"），等下一块。
     * 拿半截参数算指纹的后果不是漏判，而是每块都算出一个新指纹、每次都不命中，
     * 于是同一件事被登记成好几条待批请求。
     */
    private Optional<Denial> approvalDenial(Map<String, Object> context, Principal principal,
                                            Called call, Map<String, String> partialArgs,
                                            Set<String> settled) {
        Optional<ToolPermission> permission = permissionFor(call.name());
        if (permission.isEmpty() || !permission.get().needsApproval()) {
            return Optional.empty();
        }
        String key = call.id() == null || call.id().isBlank() ? call.name() : call.id();
        if (settled.contains(key)) {
            return Optional.empty();
        }
        String args = (call.arguments() == null ? "" : call.arguments());
        if (partialArgs != null) {
            args = partialArgs.merge(key, args, String::concat);
        }
        Optional<String> digest = CallFingerprint.of(call.name(), args);
        if (digest.isEmpty()) {
            // 参数还没收成一个完整的 JSON 对象。这不是错误，是流式的常态。
            return Optional.empty();
        }
        settled.add(key);
        Optional<ApprovalLedger.Grant> granted = gate.redeem(principal.userId(), call.name(), digest.get());
        if (granted.isPresent()) {
            // 记下"哪条批准放行的"（P07）：执行发生在工具回调里，那里没有这份上下文，
            // 而"这次调用是哪次批准的"只能靠这一句连起来。
            if (audit != null) {
                audit.granted(runIdOf(context), call.name(), digest.get(), granted.get().id());
            }
            return Optional.empty();
        }
        // 没有批准：登记一条，并把这件事停在这一步（P02）。
        Optional<String> approvalId = gate.ask(new ApprovalLedger.Ask(runIdOf(context),
                principal.userId(), call.name(), digest.get(), summaryOf(args),
                originOf(context, principal)));
        return Optional.of(new Denial(call.name(),
                approvalText(permission.get(), call.name(), approvalId.orElse(null)),
                OUTCOME_NEEDS_APPROVAL, args, approvalId.orElse(null)));
    }

    /**
     * 一条拦下来的调用落两笔账：指标（今晚要不要改配置）与审计（三个月后谁批的）。
     *
     * <p><b>为什么要分开记</b>：指标是"现在"的数，滚动窗口一过就没了；审计是"事后"的行，
     * 要留 90 天。同一次拒绝在这两处各留一份不是重复——把审计做成从指标里读，
     * 等于把"查三个月前的一次越权"交给一个已经被清过好几轮的计数器。
     *
     * <p>审计写不进去不影响这一轮的判定（{@code ToolCallAudit} 自己消化降级）：
     * 拦还是要拦的。
     */
    private void onDenial(Denial denial, Map<String, Object> context) {
        countOutcome(denial);
        if (audit == null) {
            return;
        }
        if (OUTCOME_NEEDS_APPROVAL.equals(denial.outcome())) {
            // 等审批不写审计（ADR-0027）：那件事的真相在 approval_request 一张表里
            // （谁提的、批没批、谁批的、什么时候）。写两遍的代价是"今天有几条被拒"
            // 要 union 两张表，而两张迟早对不上。
            return;
        }
        audit.rejected(scopeOf(context), denial.tool(), denial.args());
    }

    /** 被拦下来的那一次落在谁、哪一轮、哪段会话上。取不到就为空，审计照样写。 */
    private static AuditEvent.Scope scopeOf(Map<String, Object> context) {
        return AuditEvent.Scope.of(runIdOf(context), sessionKeyOf(context), principalOf(context), null);
    }

    /**
     * 这一轮属于哪段会话。为 null 表示这条 advisor 链上没带会话标识——
     * 那时审计仍有 runId 与 actor，按会话查不到，但"谁让什么工具做了什么"还在。
     */
    private static String sessionKeyOf(Map<String, Object> context) {
        Object value = context == null ? null : context.get(ChatMemory.CONVERSATION_ID);
        return value instanceof String key && !key.isBlank() ? key : null;
    }

    /**
     * 拦下来的调用计数。
     *
     * <p>这条指标是"谁能干什么"的运行时证据：yml 里写着 read-only 读不到 sales_order，
     * 那只是声明；这个数才说明真的有人试过并且被拦下来了。工具侧的
     * {@code MeteredToolCallback} 看不到被拒的调用——工具根本没被执行——所以只能在这里记。
     *
     * <p>等审批也记在这里、但 outcome 不同：越权要改配置，等批要找人点按钮，
     * 混在一个数里，运维看到"今天拒了 40 次"分不出该做哪件事。
     */
    private void countOutcome(Denial denial) {
        Counter.builder("claw4j.tool.calls")
                .description("工具调用次数")
                .tag("tool", denial.tool())
                .tag("outcome", denial.outcome())
                .register(registry)
                .increment();
    }

    /**
     * 拒绝话术要**说清是哪一条不满足**：动作还是表。
     * 只说"没有权限"的话，运营同学没法判断是该给动作还是该给表，最后往往直接给个 "*" 了事。
     */
    private static String denialText(Principal principal, String toolName, ToolPermission required) {
        if (!principal.canPerform(required.action())) {
            return DENIAL_PREFIX + "：角色 " + principal.role()
                    + " 不允许执行 " + required.action() + " 动作（工具 " + toolName + "）。";
        }
        List<String> forbidden = required.tables()
                .stream()
                .filter(table -> !principal.canReadTable(table))
                .sorted()
                .toList();
        return DENIAL_PREFIX + "：角色 " + principal.role()
                + " 不允许访问表 " + String.join("、", forbidden) + "（工具 " + toolName + "）。";
    }

    /**
     * 等审批那句话。
     *
     * <p>四件事都要说清：<b>哪个工具</b>、<b>它凭什么被拦</b>（不是没权限，是没人批）、
     * <b>审批编号是多少</b>、<b>这一轮该干什么</b>。
     * 第三件是 P02 加的：用户拿着编号才能去问"批了吗"，而没有编号时他能做的
     * 只有再发一遍同一句话——那既不会变成批准，又会把真正的进展挤掉。
     *
     * <p>最后一句是写给模型的：工具调用是同步的，模型看不到"批了没有"，
     * 不说这一句它下一轮会把同一个请求原样再发一遍。
     *
     * <p><b>这里不承诺有效期</b>：多久作废是配置（{@code claw4j.approval.ttl}），
     * 而这个组件读不到它。写死"十分钟"的话，改了配置的说法就跟卡片上剩下的时间对不上——
     * 那种偏差只会在"我以为还有五分钟"的时候才被发现。有效期由卡片按库里的
     * {@code expires_at} 显示，那里是唯一的来源。
     *
     * @param approvalId 审批编号，null 表示这一轮连请求都没登记上（没装审批设施、或库写不进去）。
     *                   那种情况下话术要如实说"没人能批"，而不是让用户去找一个不存在的编号
     */
    private static String approvalText(ToolPermission required, String toolName, String approvalId) {
        StringBuilder text = new StringBuilder(APPROVAL_PREFIX)
                .append("：工具 ").append(toolName).append(" 会执行 ").append(required.action())
                .append(" 动作，需要有人批准。");
        if (approvalId == null) {
            text.append("而这一轮没能登记成一条审批请求（没有审批设施，或者库写不进去），")
                    .append("所以在修好之前它不会被放行。");
        } else {
            text.append("审批编号 ").append(approvalId)
                    .append("。这件事已经停在这一步，批准之后会自动接着做，")
                    .append("不用重新说明背景。");
        }
        return text.append("这一轮不要重试同一个请求：再发一次也不会变成批准，")
                .append("也不会生成新的审批。").toString();
    }

    private Optional<ToolPermission> permissionFor(String toolName) {
        for (ToolPermissionRegistry registry : registries) {
            Optional<ToolPermission> permission = registry.permissionFor(toolName);
            if (permission.isPresent()) {
                return permission;
            }
        }
        return Optional.empty();
    }

    private static Principal principalOf(Map<String, Object> context) {
        Object value = context == null ? null : context.get(PRINCIPAL_CONTEXT_KEY);
        return value instanceof Principal principal ? principal : null;
    }

    /**
     * 这一轮落在哪件事上。为 null 表示不在一个 run 里（子 Agent 那一层，A10）——
     * 那时审批照常拦，只是没有"停在哪儿"这回事。
     */
    /**
     * 这条请求从哪段对话来（ADR-0027）。批准之后就在这段对话里开新一轮，
     * 而不用拿 runId 去唤醒一件"挂着的事"。
     *
     * <p>拿不到会话键时返回 null：子 Agent 那一层可能还没有会话（A10）。
     * 那时结论照样落得下来，只是没有"接着做"这回事——{@code ApprovalService} 会如实说出来。
     */
    private static ApprovalLedger.Origin originOf(Map<String, Object> context, Principal principal) {
        Object session = context == null ? null : context.get(ChatMemory.CONVERSATION_ID);
        if (!(session instanceof String sessionKey) || sessionKey.isBlank()) {
            return null;
        }
        return new ApprovalLedger.Origin(sessionKey, textAt(context, ContextKeys.CHANNEL),
                textAt(context, ContextKeys.CHAT_REF), principal);
    }

    private static String textAt(Map<String, Object> context, String key) {
        Object value = context == null ? null : context.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String runIdOf(Map<String, Object> context) {
        Object value = context == null ? null : context.get(ContextKeys.TURN_ID);
        return value instanceof String runId && !runId.isBlank() ? runId : null;
    }

    /**
     * 给人看的参数摘要：脱敏之后截断。
     *
     * <p>脱敏在截断之前：反过来的话，一个手机号被截成两半，两半都不再匹配号码规则，
     * 于是掩码失效、前半截原样进库。
     */
    private static String summaryOf(String argsJson) {
        String masked = PiiRedactor.mask(argsJson);
        return masked.length() <= ARGS_SUMMARY_CHARS
                ? masked : masked.substring(0, ARGS_SUMMARY_CHARS) + "…";
    }

    /**
     * 这一轮模型要调的调用。<b>id 与参数都要带回来</b>：授权只看名字，
     * 审批要按 id 累积分片参数、按参数算指纹。
     *
     * <p>名字为空的分块（流式下参数是一点一点送的）直接跳过——
     * 那种分块没法判定，能判定的第一块一定带着名字。
     */
    private static List<Called> requestedCalls(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResults() == null) {
            return List.of();
        }
        return chatResponse.getResults()
                .stream()
                .map(Generation::getOutput)
                .filter(Objects::nonNull)
                .map(AssistantMessage::getToolCalls)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .filter(call -> call.name() != null && !call.name().isBlank())
                .map(call -> new Called(call.id(), call.name(), call.arguments()))
                .toList();
    }

    /**
     * 把这一轮换成一句明确的拒绝。必须连工具调用一起换掉：
     * 只换正文的话工具循环看到的仍然是一个"要调工具"的响应，
     * 工具照跑，客户端拿到的是"拒绝 + 真数据"这种自相矛盾的结果。
     */
    private static ChatClientResponse replacedWith(ChatClientResponse response, String text) {
        ChatResponse.Builder builder = ChatResponse.builder();
        if (response.chatResponse() != null) {
            builder.from(response.chatResponse());
        }
        ChatResponse denied = builder.generations(List.of(new Generation(new AssistantMessage(text)))).build();
        return response.mutate().chatResponse(denied).build();
    }
}
