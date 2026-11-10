package io.github.claw4j.eval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 一条评测用例：问什么、谁问的、答对要出现什么、不许出现什么、该调哪个工具。
 *
 * <p>判分只锚数字与标识符。措辞类判据（「回答里要有『上周卖得不错』」）换个说法就红，
 * 而假红的下场通常是被顺手放宽。{@code 128400} 无法同义替换，只有查对了才会出现；
 * 措辞留给人工看报告。
 *
 * <p>身份只写名字，四档身份集中定义在 {@code eval/cases/principals.yaml}。越权用例靠
 * 「同一个问题换个身份，结论必须相反」立起来；把表清单抄进每条用例，读一百处才能知道
 * {@code guest} 能看什么，写错一处那条用例就变成了验证一个不存在的身份。
 *
 * <p>不认识的键在 {@link #from} 里当场拒掉：{@code mustMention} 敲成 {@code mustmention}
 * 时，宽松的解析器会把用例变成什么都不判，而它跑起来是全绿的。
 *
 * @param id 全局唯一，报告与报错都靠它定位；kebab-case，改了等于换一条用例
 * @param question 问给 agent 的那句话
 * @param principal 身份名字，指向 {@code principals.yaml} 里的一档
 * @param setup 判分之前在同一个会话里说的话，多轮记忆用例用；它的回答不判分
 * @param mustMention 回答里必须出现的数字或标识符；空表示不判命中
 * @param mustNotMention 回答里一个都不许出现的数字或标识符，比如别的区的数、明文手机号
 * @param expectTools 该调的工具，按集合包含比：多调不算错，少调才错
 * @param expectRefusal 这条用例期待明确的拒绝：回答里要有
 *     {@code AuthorizationAdvisor.DENIAL_PREFIX}，且不许漏数
 * @param note 给人看的备注，不参与判分
 */
public record EvalCase(String id,
                       String question,
                       String principal,
                       List<String> setup,
                       List<String> mustMention,
                       List<String> mustNotMention,
                       List<String> expectTools,
                       boolean expectRefusal,
                       String note) {

    /**
     * 锚点的长度上限。
     *
     * <p>没有它，「这句话里有 7 个字所以含数字」也能过关，那等于把自然语言短句放回判分，
     * 只是绕了一道。24 个字符够装一个数字、一个订单号或一个文档名。
     */
    private static final int MAX_ANCHOR_CHARS = 24;

    /** 用例 id 的形状：{@code sales-weekly-east}。报告靠它定位，重名会让两条互相覆盖。 */
    private static final Pattern KEBAB = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /** 工具名的形状：字母或下划线开头，允许点与横线，MCP 工具名会带这两样。 */
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    /** YAML 里认得的键。多出一个就是写错了，见类注释。 */
    private static final Set<String> KEYS = Set.of("id", "question", "principal", "setup",
            "mustMention", "mustNotMention", "expectTools", "expectRefusal", "note");

    public EvalCase {
        id = requireText(id, "id", null);
        if (!KEBAB.matcher(id).matches()) {
            throw new IllegalArgumentException("用例 id 必须是 kebab-case（小写、用横线连）：" + id);
        }
        question = requireText(question, "question", id);
        principal = requireText(principal, "principal", id);
        setup = copyOf(setup, "setup", id);
        mustMention = copyOf(mustMention, "mustMention", id);
        mustNotMention = copyOf(mustNotMention, "mustNotMention", id);
        expectTools = copyOf(expectTools, "expectTools", id);
        for (String anchor : mustMention) {
            requireAnchor(anchor, "mustMention", id);
        }
        for (String anchor : mustNotMention) {
            requireAnchor(anchor, "mustNotMention", id);
        }
        for (String tool : expectTools) {
            if (!TOOL_NAME.matcher(tool).matches()) {
                throw new IllegalArgumentException("用例 " + id + " 的 expectTools 里有不像工具名的项：" + tool);
            }
        }
    }

    /**
     * 从一行已解析的 YAML 造一条用例。
     *
     * <p>收的是 {@code Map}，不碰文件：解析归执行器，判分归本模块，用例写错了能在回归里
     * 发现，不用连模型。
     *
     * @throws IllegalArgumentException 缺字段、类型不对、键写错、锚点像自然语言短句
     */
    public static EvalCase from(Map<String, Object> raw) {
        if (raw == null) {
            throw new IllegalArgumentException("用例是一个空条目");
        }
        Set<String> unknown = new LinkedHashSet<>(raw.keySet());
        unknown.removeAll(KEYS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("用例 " + raw.get("id") + " 有认不出的键：" + unknown
                    + "（认得的是 " + KEYS + "）");
        }
        // 缺这个键与「不该调工具」是两件事：后者要显式写 []。漏写时判成空列表，
        // 一条本该调工具的用例会静默变成不判轨迹，而它跑起来是全绿的。
        if (!raw.containsKey("expectTools")) {
            throw new IllegalArgumentException("用例 " + raw.get("id")
                    + " 缺少 expectTools（不该调工具时写 []，不要省掉）");
        }
        return new EvalCase(
                text(raw, "id"),
                text(raw, "question"),
                text(raw, "principal"),
                strings(raw, "setup"),
                strings(raw, "mustMention"),
                strings(raw, "mustNotMention"),
                strings(raw, "expectTools"),
                flag(raw, "expectRefusal"),
                text(raw, "note"));
    }

    /**
     * 这个值能不能当锚点：含 ASCII 数字，或者整体是一个标识符。
     *
     * <p>允许 {@code 7 天} 这类带单位的值，中文的数字几乎总带单位，硬要求纯数字会把
     * 「退货窗口几天」这种用例挡在门外。判 ASCII 而不用 {@code Character.isDigit}：
     * 后者认全角，{@code １２３} 会被当成合法锚点，而模型的回答里不会出现全角写法，
     * 那条用例从写下那天就是红的。
     */
    static boolean looksLikeAnchor(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_ANCHOR_CHARS) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                return true;
            }
        }
        return TOOL_NAME.matcher(value).matches();
    }

    private static void requireAnchor(String anchor, String field, String id) {
        if (!looksLikeAnchor(anchor)) {
            throw new IllegalArgumentException("用例 " + id + " 的 " + field
                    + " 里有不像数字或标识符的项：" + anchor
                    + "（判分只锚数字与标识符，措辞类判断留给人看报告）");
        }
    }

    private static String requireText(String value, String field, String id) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException((id == null ? "用例" : "用例 " + id) + " 缺少 " + field);
        }
        return value.strip();
    }

    private static List<String> copyOf(List<String> values, String field, String id) {
        if (values == null) {
            return List.of();
        }
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            copy.add(requireText(value, field, id));
        }
        return List.copyOf(copy);
    }

    private static String text(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        // YAML 里把 128400 写成裸数字很自然。toString 成字符串不影响判分，
        // 但对象与列表绑进来必须是错。
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw new IllegalArgumentException("用例 " + raw.get("id") + " 的 " + key + " 不是一行文本：" + value);
    }

    private static List<String> strings(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof String single) {
            return List.of(single);
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("用例 " + raw.get("id") + " 的 " + key
                    + " 应当是列表：" + value);
        }
        List<String> items = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item == null) {
                throw new IllegalArgumentException("用例 " + raw.get("id") + " 的 " + key + " 里有空项");
            }
            items.add(String.valueOf(item));
        }
        return items;
    }

    private static boolean flag(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        // 「true」这种写法在 YAML 里合法，但与字符串容易混淆，而判错的方向是本来该拒的没拒。
        throw new IllegalArgumentException("用例 " + raw.get("id") + " 的 " + key + " 应当是 true / false："
                + value);
    }

    /** 这条用例属于哪一类（取自 id 的前缀），报告与过滤用。 */
    public String category() {
        int dash = id.indexOf('-');
        return dash < 0 ? id : id.substring(0, dash);
    }
}
