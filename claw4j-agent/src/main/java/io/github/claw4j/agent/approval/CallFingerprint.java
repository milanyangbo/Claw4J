package io.github.claw4j.agent.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 一次工具调用的参数指纹（P02）：{@code sha256(工具名 + 规范化参数)}。
 *
 * <p><b>为什么批准要绑到参数上</b>：只按工具名批的话，"批了查一次上周成交额"同时
 * 放行了"查全表"。批准的对象必须是"这一次具体会跑起来的那个调用"，
 * 所以参数一变就是另一次，要重新批。
 *
 * <p><b>为什么不含 runId、不含序号</b>：这是与幂等键（ADR-0017）划清界限的那一条，
 * 也是本类最容易被人"顺手改对"的地方。幂等键要区分每一次执行——同一件事里同一个调用
 * 出现两次，第二次要认得出来（否则重复发一封邮件）。审批指纹相反：一次点头要覆盖
 * "这类操作"，否则每个新任务都要重新批一遍，人会开始闭眼点。
 * <b>两个哈希长得很像、口径完全相反</b>：把 runId 混进来，跨任务复用批准这件事就悄悄没了；
 * 把本类的值当幂等键用，同一件事里第二次同参数调用会被当成"已经做过"跳过。
 * 单独一个类、单独一个名字、注释写死这两句，是因为散在调用点上的话，下一次改动就分不清了。
 *
 * <p><b>规范化是必需的，不是整洁</b>：模型每次生成的参数顺序不保证一样
 * （{@code {"a":1,"b":2}} 与 {@code {"b":2,"a":1}} 是同一次调用）。不排序的话，
 * 同一个调用换个字段顺序就得到新指纹，等于让人重新批一遍同一件事。
 * 递归排序（不只顶层），因为{"查询条件":{"起":..,"止":..}} 这种嵌套才是常态。
 *
 * <p><b>数字要归一</b>：{@code 1}、{@code 1.0}、{@code 1.00} 在 JSON 里是同一个数，
 * 模型写哪一种都有可能。按 {@link BigDecimal#stripTrailingZeros()} 归一，
 * 不做的话"步数 1"与"步数 1.0"会成为两次不同的操作。
 */
public final class CallFingerprint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CallFingerprint() {
    }

    /**
     * 算一个指纹。
     *
     * @param toolName      工具名，不是动作名：动作是权限的口径，批准的对象是工具
     * @param argumentsJson 模型给的参数原文
     * @return 空表示"参数还不成一个 JSON 对象"——流式下工具调用的参数是分片到达的，
     *         收到一半时算出来的指纹会让每收到一块就变一次，那种指纹不能用来比对。
     *         调用方据此**等下一块**，而不是拿一个半截参数去问账本
     */
    public static Optional<String> of(String toolName, String argumentsJson) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        Optional<String> canonical = canonicalArgs(argumentsJson);
        return canonical.map(args -> sha256(toolName + '\n' + args));
    }

    /**
     * 规范化参数：对象按 key 递归排序、数字归一、空白去掉。
     *
     * <p>空参数与 null 参数都归一成 {@code {}}：模型不给参数时写的是哪一种都不该影响指纹。
     *
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
        } catch (Exception notJsonYet) {
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
            // 排序是这一整个类存在的理由：不排则换个字段顺序就是另一次调用。
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
        } catch (NoSuchAlgorithmException impossible) {
            // 每个 JRE 都必须有 SHA-256；没有的话整个审批链都不该继续。
            throw new IllegalStateException("JRE 不支持 SHA-256", impossible);
        }
    }
}
