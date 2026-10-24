package io.github.claw4j.tools.generator;

import java.util.Locale;
import java.util.Optional;

/**
 * 生成器接受的脚本语言。
 *
 * <p><b>只认两种，多一种都不行</b>：静态检查是按语言的语法与惯用法写的，
 * 它对一种不认识的语言什么也判断不了——那种情形下"检查通过"是一句空话，
 * 而空话会被当成承诺用。不认识的入口一律拒绝，理由写在
 * {@link ScriptStaticCheck} 的 {@code UNKNOWN_LANGUAGE} 上。
 */
public enum ScriptLanguage {

    SHELL("sh"),
    PYTHON("py");

    private final String extension;

    ScriptLanguage(String extension) {
        this.extension = extension;
    }

    /**
     * 按入口文件名判定；认不出来就返回空，由调用方拒绝。
     *
     * <p>判定依据是扩展名而不是 shebang：shebang 是脚本<b>自己写</b>的一行，
     * 而"这是个什么脚本"应当是提交者声明的事实。靠脚本自己说的话去选规则集，
     * 等于让被检查的对象决定用哪套规则检查它。
     */
    public static Optional<ScriptLanguage> ofEntrypoint(String entrypoint) {
        if (entrypoint == null) {
            return Optional.empty();
        }
        String name = entrypoint.toLowerCase(Locale.ROOT);
        for (ScriptLanguage language : values()) {
            if (name.endsWith("." + language.extension)) {
                return Optional.of(language);
            }
        }
        return Optional.empty();
    }
}
