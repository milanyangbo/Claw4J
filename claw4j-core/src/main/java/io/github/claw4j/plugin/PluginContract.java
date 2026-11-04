package io.github.claw4j.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * 插件与核心之间的版本契约（P16）：每个插件声明它要的核心区间，启动时对一次。
 *
 * <p>契约文件是插件 jar 里的 {@value #FILE}：
 *
 * <pre>
 * name=sales
 * requires-core=&gt;=0.1.0 &lt;0.2.0
 * </pre>
 *
 * <p>{@code requires-core} <b>必填</b>：缺了就算违规，不按"任意版本"兜底——一个漏写的键
 * 被读成"什么都要"，等于这道闸在最重要的一行上自动放行。{@code name} 选填，只进报错消息。
 * 未知的键忽略，留给后来的版本往前加字段。
 *
 * <p><b>这里只做判断，不做 IO，也不抛</b>：读文件、扫 classpath、让启动停下来是
 * {@code claw4j-app} 那边的事（它要发在启动路径上，而 core 连 Spring 都不依赖）。
 * 判断能一次给全部违规（而不是第一条就抛），
 * 是因为升级核心的人想知道"是不是只有这一个"——修一个重启一次，是另一种折磨。
 */
public final class PluginContract {

    /** 契约文件在插件 jar 里的位置。 */
    public static final String FILE = "META-INF/claw4j-plugin.properties";

    /** 插件名，只用于报错。 */
    public static final String KEY_NAME = "name";

    /** 它需要的核心版本区间。必填。 */
    public static final String KEY_REQUIRES_CORE = "requires-core";

    private PluginContract() {
    }

    /**
     * 一条不成立的声明。
     *
     * @param name   插件名；没写 {@code name} 时是占位符，消息里仍然有来源
     * @param source 契约文件的来源（jar 里是 {@code jar:file:/...!/META-INF/...}）
     * @param reason 为什么不行，一句完整的话
     */
    public record Violation(String name, String source, String reason) {

        /** 报错里的一行。带来源，是因为插件常常是好几个 jar，只说名字定位不到文件。 */
        public String message() {
            return "插件 " + name + " " + reason + "（声明文件：" + source + "）";
        }
    }

    /**
     * 逐个文件判一遍。
     *
     * @param core     当前核心版本，<b>用于比较</b>
     * @param coreText 当前核心版本的原样写法，<b>只用于报错</b>。两个参数不是重复：
     *                 {@link CoreVersion} 有意丢掉限定符（{@code 0.1.0-SNAPSHOT} → {@code 0.1.0}），
     *                 而报错要照抄人能在构件的 pom 里看到的那个版本号——报错把人看到的版本改写掉，
     *                 运维会先去核对"我到底部署了什么"
     * @param files    契约文件的来源 → 内容。按来源排一遍序，于是同一批配置每次得到同样的消息顺序
     * @return 所有成立的违规；空表示全部匹配
     */
    public static List<Violation> checkAll(CoreVersion core, String coreText, Map<String, Properties> files) {
        List<Violation> violations = new ArrayList<>();
        for (Map.Entry<String, Properties> file : new TreeMap<>(files).entrySet()) {
            Violation violation = checkOne(core, coreText, file.getKey(), file.getValue());
            if (violation != null) {
                violations.add(violation);
            }
        }
        return violations;
    }

    private static Violation checkOne(CoreVersion core, String coreText, String source, Properties descriptor) {
        String name = text(descriptor.getProperty(KEY_NAME));
        String declared = text(descriptor.getProperty(KEY_REQUIRES_CORE));
        if (declared.isEmpty()) {
            return new Violation(unnamed(name), source,
                    "没有声明 " + KEY_REQUIRES_CORE + "（应当写它需要的核心区间，例如 >=0.1.0 <0.2.0）");
        }
        CoreVersionRange range;
        try {
            range = CoreVersionRange.parse(declared);
        } catch (IllegalArgumentException broken) {
            return new Violation(unnamed(name), source,
                    "的 " + KEY_REQUIRES_CORE + " 读不成一个区间：\"" + declared + "\"（" + broken.getMessage() + "）");
        }
        if (range.matches(core)) {
            return null;
        }
        return new Violation(unnamed(name), source,
                "要求核心 " + declared + "，当前核心是 " + coreText);
    }

    private static String unnamed(String name) {
        return name.isEmpty() ? "（未具名）" : name;
    }

    private static String text(String raw) {
        return raw == null ? "" : raw.strip();
    }
}
