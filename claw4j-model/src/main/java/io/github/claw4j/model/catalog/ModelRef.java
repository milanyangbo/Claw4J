package io.github.claw4j.model.catalog;

import java.util.Locale;
import java.util.Objects;

/**
 * 一个模型的引用：{@code provider/model}。
 *
 * <p>照 OpenClaw 的写法（`docs/concepts/models.md`「Selection order」）。为什么要有它：
 * 配置里"用哪个模型"必须能一句话说清，而供应商与模型是两级——只写 {@code deepseek} 时，
 * 到底是哪一行？只写 {@code deepseek-chat} 时，端点是谁？合起来写既唯一又自解释。
 *
 * <p>切分只认<b>第一个</b>斜杠：模型 id 里可以带斜杠（{@code meta-llama/Llama-3}、
 * {@code Qwen/Qwen3} 这类托管命名），供应商 id 里不带。
 *
 * @param provider 供应商 id
 * @param model    模型 id
 */
public record ModelRef(String provider, String model) {

    public ModelRef {
        provider = normalize(provider, "provider");
        model = normalize(model, "model");
    }

    /**
     * 解一段引用。
     *
     * @param text 形如 {@code deepseek/deepseek-chat}
     * @return 引用
     * @throws IllegalArgumentException 没有斜杠，或某一段是空的
     */
    public static ModelRef parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("模型引用不能为空，写法是 provider/model");
        }
        int slash = text.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("模型引用 " + text
                    + " 少了斜杠。写法是 provider/model，例如 deepseek/deepseek-chat。");
        }
        return new ModelRef(text.substring(0, slash), text.substring(slash + 1));
    }

    private static String normalize(String part, String what) {
        String value = Objects.requireNonNull(part, what).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("模型引用的 " + what + " 不能为空，写法是 provider/model");
        }
        return what.equals("provider") ? value.toLowerCase(Locale.ROOT) : value;
    }

    @Override
    public String toString() {
        return provider + "/" + model;
    }
}
