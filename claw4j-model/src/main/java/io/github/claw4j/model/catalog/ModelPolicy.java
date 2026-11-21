package io.github.claw4j.model.catalog;

import java.util.ArrayList;
import java.util.List;

/**
 * 哪些模型可以被选中。形状照 OpenClaw 的 {@code modelPolicy.allow}
 * （`docs/gateway/config-agents/models.md:128`、`src/config/model-policy-ref.ts:29-49`）。
 *
 * <p>三种写法，**只认尾部 `*`**：
 * <ul>
 *   <li>精确：{@code deepseek/deepseek-chat}；
 *   <li>段前缀：{@code deepseek/*}、{@code qwen/meta-llama/*}；
 *   <li>模型名前缀：{@code qwen/family-*}。
 * </ul>
 *
 * <p>为什么不支持中间通配：它要么是正则（配置里出现正则等于把"允许哪些模型"变成一段没人审的代码），
 * 要么要靠猜。解析不了的写法在启动时直接抛，不静默忽略——静默忽略的表现是"配了白名单却全放行"。
 *
 * <p>不配或空列表 = 放行全部：这是"没设策略"，不是"拒绝一切"。
 */
public final class ModelPolicy {

    private final List<String> allow;

    private ModelPolicy(List<String> allow) {
        this.allow = List.copyOf(allow);
    }

    /**
     * 解一份白名单。
     *
     * @param allow 配置里的列表，可以为 null
     * @return 策略
     * @throws IllegalArgumentException 写法不合法
     */
    public static ModelPolicy of(List<String> allow) {
        List<String> normalized = new ArrayList<>();
        if (allow != null) {
            for (String raw : allow) {
                normalized.add(validate(raw));
            }
        }
        return new ModelPolicy(normalized);
    }

    /** 放行全部（空列表）。 */
    public static ModelPolicy allowAll() {
        return new ModelPolicy(List.of());
    }

    /**
     * 这个引用放不放行。
     *
     * @param ref 引用
     * @return 放行时为 true；白名单为空时永远 true
     */
    public boolean allows(ModelRef ref) {
        if (allow.isEmpty()) {
            return true;
        }
        return allow.stream().anyMatch(pattern -> matches(pattern, ref));
    }

    /**
     * 配置里写的那几条（已归一）。
     *
     * @return 列表
     */
    public List<String> patterns() {
        return allow;
    }

    /** 没有任何策略。 */
    public boolean isEmpty() {
        return allow.isEmpty();
    }

    private static String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("claw4j.model.policy.allow 里有一条空的。"
                    + "要么写 provider/model，要么整段删掉（空列表 = 放行全部）。");
        }
        String pattern = raw.trim();
        int slash = pattern.indexOf('/');
        if (slash <= 0 || slash == pattern.length() - 1) {
            throw new IllegalArgumentException("claw4j.model.policy.allow 里的 " + pattern
                    + " 少了斜杠或某一段是空的。写法是 provider/model、provider/*、provider/family-*。");
        }
        String provider = pattern.substring(0, slash);
        String model = pattern.substring(slash + 1);
        if (provider.contains("*")) {
            throw new IllegalArgumentException("claw4j.model.policy.allow 里的 " + pattern
                    + " 在供应商那一段用了 *。只支持「整家」写法 provider/*，不支持中间通配。");
        }
        int star = model.indexOf('*');
        if (star >= 0 && star != model.length() - 1) {
            throw new IllegalArgumentException("claw4j.model.policy.allow 里的 " + pattern
                    + " 的 * 不在末尾。只认尾部通配（provider/*、provider/family-*），"
                    + "中间通配要么是正则、要么要靠猜，两种都不要。");
        }
        return pattern;
    }

    private static boolean matches(String pattern, ModelRef ref) {
        int slash = pattern.indexOf('/');
        String provider = pattern.substring(0, slash);
        String model = pattern.substring(slash + 1);
        if (!provider.equals(ref.provider())) {
            return false;
        }
        if (model.equals("*")) {
            return true;
        }
        if (model.endsWith("*")) {
            String prefix = model.substring(0, model.length() - 1);
            return ref.model().startsWith(prefix);
        }
        return model.equals(ref.model());
    }
}
