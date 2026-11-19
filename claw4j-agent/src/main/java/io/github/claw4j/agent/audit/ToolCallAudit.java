package io.github.claw4j.agent.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>唯一一处靠进程内状态的地方：{@link #granted} 把刚兑现的那条批准记下来，等执行那一下取走。
 * 兑现发生在工具回调里、执行也在同一次调用里，但两处拿不到同一份上下文。代价写在字段上：只在单进程内
 * 成立、兑现了没执行会留下一个不会被取走的条目、键里带 runId + 工具名 + 指纹三样。
 */
public class ToolCallAudit {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 兑现与执行之间的那条线能活多久。取十分钟，与默认的审批有效期同一个量级。
     */
    private static final Duration HANDOFF_TTL = Duration.ofMinutes(10);

    /**
     * 这条线最多记多少条。它只是线索，不是账本——超了整份清掉，
     * 比"一直涨到内存告警"诚实：清掉的表现是那几行审计少了审批编号。
     */
    private static final int HANDOFF_LIMIT = 256;

    private static final Logger log = LoggerFactory.getLogger(ToolCallAudit.class);

    /** 为 null 表示这个部署没有账本：审计无处可写，于是什么都不写（而不是造一个假账本）。 */
    private final AuditSink ledger;

    /** 兑现过的批准，等着被执行那一下取走。键是 runId + 工具名 + 指纹。 */
    private final Map<String, Pending> granted = new ConcurrentHashMap<>();

    /**
     * 规则层，落库交给 {@code ledger}。
     *
     * @param ledger 审计落点，为 null 时什么都不写
     */
    public ToolCallAudit(AuditSink ledger) {
        this.ledger = ledger;
    }

    /** 一条兑现过的批准，带着它的到期点（过了就不再被取走）。 */
    private record Pending(String approvalId, Instant expiresAt) {
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
     */
    public void toolStarted(AuditEvent.Scope scope, String toolName, String argsJson) {
        if (isBlank(toolName)) {
            return;
        }
        write(AuditEvent.toolStarted(scope, toolName, fingerprint(toolName, argsJson)));
    }

    /**
     * 一次工具调用结束（成功或抛异常）。
     *
     * @param scope 这一轮的身份与位置
     * @param toolName 工具名，空白时不写
     * @param argsJson 工具参数原文，只用来算指纹
     * @param failure 工具抛出的异常类型名；null 表示正常返回。只用来判「成没成」，类型名本身不落库
     *     ——那一列是归一化错误码，而类型名是开放的。要查是哪个异常，trace 的 span 上有
     */
    public void toolFinished(AuditEvent.Scope scope, String toolName, String argsJson,
                             String failure) {
        if (isBlank(toolName)) {
            return;
        }
        String digest = fingerprint(toolName, argsJson);
        write(AuditEvent.toolFinished(scope, toolName, digest,
                failure == null ? AuditStatus.SUCCEEDED : AuditStatus.FAILED,
                failure == null ? null : AuditErrorCode.TOOL_FAILED,
                take(scope == null ? null : scope.runId(), toolName, digest)));
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
     */
    public void rejected(AuditEvent.Scope scope, String toolName, String argsJson) {
        if (isBlank(toolName)) {
            return;
        }
        write(AuditEvent.toolFinished(scope, toolName, fingerprint(toolName, argsJson),
                AuditStatus.BLOCKED, AuditErrorCode.TOOL_BLOCKED, null));
    }

    /**
     * 记下「这一条批准刚刚被兑现了」，等执行那一下把它写进结束事件。
     *
     * <p>没有 runId 时不记：不在一个 run 里的调用本来也不会落审计，记下来只占一个永远不会被取走的槽。
     *
     * @param runId 这一轮的编号
     * @param toolName 工具名
     * @param argsDigest 参数指纹
     * @param approvalId 审批编号
     */
    public void granted(String runId, String toolName, String argsDigest, String approvalId) {
        if (isBlank(runId) || isBlank(toolName) || isBlank(argsDigest) || isBlank(approvalId)) {
            return;
        }
        if (granted.size() >= HANDOFF_LIMIT) {
            granted.values().removeIf(pending -> pending.expiresAt().isBefore(Instant.now()));
            if (granted.size() >= HANDOFF_LIMIT) {
                // 清空而不是乱丢一条：这条线索没有"更重要"的那一条，而留一半只会让人以为它是完整的。
                granted.clear();
                log.info("审批与执行之间的待配对记录超过 {} 条，整份清掉；之后的审计事件不会带审批编号",
                        HANDOFF_LIMIT);
            }
        }
        granted.put(key(runId, toolName, argsDigest),
                new Pending(approvalId, Instant.now().plus(HANDOFF_TTL)));
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

    /** 取走那条"刚兑现的批准"。过期的当作没有：它已经不在有效期附近了，配上去只会误导。 */
    private String take(String runId, String toolName, String digest) {
        if (isBlank(runId) || isBlank(digest)) {
            return null;
        }
        Pending pending = granted.remove(key(runId, toolName, digest));
        if (pending == null) {
            return null;
        }
        return pending.expiresAt().isBefore(Instant.now()) ? null : pending.approvalId();
    }

    private static String key(String runId, String toolName, String argsDigest) {
        return runId + '\n' + toolName + '\n' + argsDigest;
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
     * <p>不含 {@code runId}、不含序号：批准要跨执行复用（与幂等键口径相反）。把 {@code runId}
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
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
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
