package io.github.claw4j.agent.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 审计的规则层（ADR-0027 决定二）：把「什么时候该记一条什么事件」收在一处，落库交给
 * {@link AuditLedger}。
 *
 * <p>它记的是事件，不是「某次调用的行」：一次工具调用产生两条（{@code tool.action.started} 与
 * {@code tool.action.finished}），一次运行两条（{@code agent.run.started} 与
 * {@code agent.run.finished}）。这是 OpenClaw 的形状（{@code packages/gateway-protocol/src/schema/audit.ts:10}），
 * 也是审计能回答「跑了多久」的前提——只有结束那一条时，两次调用的先后只能靠时间戳猜。
 *
 * <p>等审批不写审计：那件事的真相在 {@code approval_request} 一张表里（谁提的、批没批、谁批的、
 * 什么时候）。同一个事实写两遍，第一个代价是「今天有几条被拒」要 union 两张表，第二个代价是两张表
 * 迟早对不上。
 *
 * <p>只写元数据：参数原文、结果、异常消息都不进来。指纹用 {@link #digest}——审批那一侧算的也是
 * 这个函数，于是「这次执行是哪次批准的」能直接对上，而参数本身不进库。
 *
 * <p>唯一一处靠外部状态的地方：{@link #granted} 把刚兑现的那条批准记下来，等执行那一下取走。
 * 那份状态是 {@link DecisionFacts}——落到 {@code execution_decision_fact} 一张表里（ADR-0037 票 05），
 * 不再是进程内 Map：重启与多实例都还在，审计里的审批编号不会成批消失。
 */
public class ToolCallAudit {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final Logger log = LoggerFactory.getLogger(ToolCallAudit.class);

    /** 为 null 表示这个部署没有账本：审计无处可写，于是什么都不写（而不是造一个假账本）。 */
    private final AuditSink ledger;

    /** 审批与执行的交接；为 null 表示没有落点，此时审计行不带审批编号（不是故障）。 */
    private final DecisionFacts facts;

    /**
     * 只写事件、不做审批交接。
     *
     * @param ledger 审计落点，为 null 时什么都不写
     */
    public ToolCallAudit(AuditSink ledger) {
        this(ledger, null);
    }

    /**
     * 规则层，落库交给 {@code ledger}，审批交接交给 {@code facts}。
     *
     * @param ledger 审计落点，为 null 时什么都不写
     * @param facts 审批交接，为 null 时结束事件不带审批编号
     */
    public ToolCallAudit(AuditSink ledger, DecisionFacts facts) {
        this.ledger = ledger;
        this.facts = facts;
    }

    /**
     * 一轮开始。
     *
     * @param scope 这一轮的身份与位置
     */
    public void runStarted(AuditEvent.Scope scope) {
        write(AuditEvent.runStarted(scope));
    }

    /**
     * 一轮结束。
     *
     * @param scope 这一轮的身份与位置
     * @param status 这一轮的结局
     * @param errorCode 归一化错误码，成功时为 null
     */
    public void runFinished(AuditEvent.Scope scope, AuditStatus status, AuditErrorCode errorCode) {
        write(AuditEvent.runFinished(scope, status, errorCode));
    }

    /**
     * 一次工具调用开始。
     *
     * <p>被策略挡住的调用不该调它：那种调用根本没有开始，只有一条 {@code finished / blocked}。
     * 两条都写的话，配额统计会把「想调但没调成」算成「调过」。
     *
     * @param scope 这一轮的身份与位置
     * @param toolName 工具名，空白时不写
     * @param argsJson 工具参数原文，只用来算指纹
     * @param toolCallId 这次调用的编号（票 06），可为 null——没开观测或 provider 不给 id 时就是没有，
     *     不编一个来顶
     */
    public void toolStarted(AuditEvent.Scope scope, String toolName, String argsJson,
                            String toolCallId) {
        if (isBlank(toolName)) {
            return;
        }
        write(AuditEvent.toolStarted(scope, toolCallId, toolName, fingerprint(toolName, argsJson)));
    }

    /**
     * 一次工具调用结束（成功或抛异常）。
     *
     * @param scope 这一轮的身份与位置
     * @param toolName 工具名，空白时不写
     * @param argsJson 工具参数原文，只用来算指纹
     * @param failure 工具抛出的异常类型名；null 表示正常返回。只用来判「成没成」，类型名本身不落库
     *     ——那一列是归一化错误码，而类型名是开放的。要查是哪个异常，trace 的 span 上有
     * @param toolCallId 这次调用的编号，可为 null
     */
    public void toolFinished(AuditEvent.Scope scope, String toolName, String argsJson,
                             String failure, String toolCallId) {
        if (isBlank(toolName)) {
            return;
        }
        String digest = fingerprint(toolName, argsJson);
        write(AuditEvent.toolFinished(scope, toolCallId, toolName, digest,
                failure == null ? AuditStatus.SUCCEEDED : AuditStatus.FAILED,
                failure == null ? null : AuditErrorCode.TOOL_FAILED,
                take(scope == null ? null : scope.executionId(), toolName, digest)));
    }

    /**
     * 被拦下来了，而且再等也不会通过：没权限、或者这个工具的权限根本没登记。
     *
     * <p>只写一条 {@code finished / blocked}：动作没有开始，就没有开始那一条。回给模型的那句话
     * （「缺 tools 权限」）不进来——审计记的是归一化错误码，那句话的语气会随文案改动而变，把它写进
     * 「为什么没成」那一格，三个月后按它做统计就废了。
     *
     * @param scope 这一轮的身份与位置
     * @param toolName 工具名，空白时不写
     * @param argsJson 工具参数原文，只用来算指纹
     * @param toolCallId 这次调用的编号，可为 null。审批闸在计量层之外，拦下时 {@code ToolContext}
     *     里通常还没有这个键——那是顺序决定的，不是漏传
     */
    public void rejected(AuditEvent.Scope scope, String toolName, String argsJson,
                         String toolCallId) {
        if (isBlank(toolName)) {
            return;
        }
        write(AuditEvent.toolFinished(scope, toolCallId, toolName, fingerprint(toolName, argsJson),
                AuditStatus.BLOCKED, AuditErrorCode.TOOL_BLOCKED, null));
    }

    /**
     * 记下「这一条批准刚刚被兑现了」，等执行那一下把它写进结束事件。
     *
     * <p>没有 executionId 时不记：不在一个 run 里的调用本来也不会落审计，记下来只占一个永远不会被取走的槽。
     *
     * @param executionId 这一轮的编号
     * @param toolName 工具名
     * @param argsDigest 参数指纹
     * @param approvalId 审批编号
     */
    public void granted(String executionId, String toolName, String argsDigest, String approvalId) {
        if (facts == null) {
            return;
        }
        facts.grant(executionId, toolName, argsDigest, approvalId);
    }

    /** 写一条。写不进去只记一条 debug：审计缺一行不该把一次调用打成失败。 */
    private void write(AuditEvent event) {
        if (ledger == null) {
            return;
        }
        try {
            ledger.record(event);
        } catch (RuntimeException failure) {
            // 账本自己已经吃掉了存储异常；能走到这里的是更意外的东西，仍然不该影响这一次调用。
            log.debug("写审计事件时出错：{}", event.action().column(), failure);
        }
    }

    /** 取走那条"刚兑现的批准"。取一次就没了：同一条批准不为第二次执行放行。 */
    private String take(String executionId, String toolName, String digest) {
        if (facts == null) {
            return null;
        }
        return facts.take(executionId, toolName, digest);
    }

    /**
     * 参数指纹：{@code sha256(工具名 + 规范化参数)}。
     *
     * <p>它是<b>唯一</b>一份：审批拿它当"一次具体调用"的键，审计拿它当"本次执行是哪次批准"的配对键。
     * 两处各写一份的话，键长得一样但算出来不同，配不上而且不报错——那正是这条链最坏的失败形态。
     *
     * <p>规范化是必需的：模型每次生成的参数顺序不保证一样（{@code {"a":1,"b":2}} 与
     * {@code {"b":2,"a":1}} 是同一次调用）。不排序的话，换个字段顺序就得到新指纹，等于让人重新批
     * 一遍同一件事。递归排序而不只顶层，因为嵌套对象才是常态。数字按
     * {@link java.math.BigDecimal#stripTrailingZeros()} 归一：{@code 1} / {@code 1.0} / {@code 1.00}
     * 在 JSON 里是同一个数。
     *
     * <p>不含 {@code executionId}、不含序号：批准要跨执行复用（与幂等键口径相反）。把 {@code executionId}
     * 混进来，跨任务复用批准就悄悄没了。
     *
     * @param toolName 工具名
     * @param argsJson 模型给的参数原文
     * @return 空表示参数还不成一个 JSON 对象（流式下参数分片到达，半截指纹不能用来比对）
     */
    public static Optional<String> digest(String toolName, String argsJson) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return canonicalArgs(argsJson).map(args -> sha256(toolName + '\n' + args));
    }

    /**
     * 参数指纹，与审批那条同一个函数。参数还没收全时为空——
     * 那时连"这一次是什么"都还说不清，记一个算出来的半截指纹比留空更糟。
     */
    static String fingerprint(String toolName, String argsJson) {
        return digest(toolName, argsJson).orElse(null);
    }

    /**
     * 规范化参数：对象按 key 递归排序、数字归一、空白去掉。
     *
     * <p>空参数与 null 参数都归一成 {@code {}}：模型不给参数时写哪一种都不该影响指纹。
     *
     * @param argumentsJson 参数原文，可为 null
     * @return 空表示参数不是一段完整的 JSON 对象
     */
    public static Optional<String> canonicalArgs(String argumentsJson) {
        String text = argumentsJson == null ? "" : argumentsJson.strip();
        if (text.isEmpty()) {
            return Optional.of("{}");
        }
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(text);
        }
        catch (Exception notJsonYet) {
            // 流式下的常态：参数还在路上。不是错误，所以不抛、不打日志。
            return Optional.empty();
        }
        if (parsed == null || parsed.isMissingNode()) {
            return Optional.empty();
        }
        return Optional.of(canonical(parsed));
    }

    private static String canonical(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>(node.propertyNames());
            Collections.sort(names);
            StringBuilder text = new StringBuilder("{");
            for (String name : names) {
                if (text.length() > 1) {
                    text.append(',');
                }
                text.append(quoted(name)).append(':').append(canonical(node.get(name)));
            }
            return text.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder text = new StringBuilder("[");
            for (JsonNode child : node) {
                if (text.length() > 1) {
                    text.append(',');
                }
                text.append(canonical(child));
            }
            return text.append(']').toString();
        }
        if (node.isNumber()) {
            return node.decimalValue().stripTrailingZeros().toPlainString();
        }
        if (node.isTextual()) {
            return quoted(node.textValue());
        }
        return node.toString();
    }

    /** JSON 字符串的引号与转义。手写而不是再过一次 Jackson：过一遍会把 key 顺序又交回给它。 */
    private static String quoted(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                case '\t' -> text.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        text.append(String.format("\\u%04x", (int) ch));
                    } else {
                        text.append(ch);
                    }
                }
            }
        }
        return text.append('"').toString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException impossible) {
            // 每个 JRE 都必须有 SHA-256；没有的话整个审批链都不该继续。
            throw new IllegalStateException("JRE 不支持 SHA-256", impossible);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
