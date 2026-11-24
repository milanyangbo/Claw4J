package io.github.claw4j.capability.untrusted;

import io.github.claw4j.domain.untrusted.ExternalContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 给工具返回值贴来源：包装（外部内容）或只净化特殊 token（所有工具）。
 *
 * <p>为什么是装饰器而不是改库：{@link ToolCallback} 只有两个方法要看（{@code getToolDefinition()} 与
 * {@code call}），包一层不用碰社区包的类，也不用给每个 provider 写一遍。
 *
 * <p>两种模式的差别只在一处：
 *
 * <ul>
 *   <li>{@link #wrapping}：加随机 id 边界与来源行。用于网页、搜索、脚本、知识库、子 Agent——
 *       这些内容的"从哪来"必须让模型看见。</li>
 *   <li>{@link #sanitizing}：只把模型特殊 token 换成占位符，<b>不加任何标记</b>。用于"所有工具"，
 *       包括按设计不包装的 {@code Read} / {@code Bash} / MCP。这一层比 OpenClaw 严：
 *       它的特殊 token 净化只作用于被包装的内容。</li>
 * </ul>
 *
 * <p>两层叠加是安全的：{@link #sanitizing} 发现内容已经被包过（有合法 id 的边界）就原样放行——
 * 否则它会把人家的边界当伪造标记清掉。
 */
public final class UntrustedToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(UntrustedToolCallback.class);

    private final ToolCallback delegate;

    private final ExternalContent.Source source;

    private final boolean withWarning;

    private UntrustedToolCallback(ToolCallback delegate, ExternalContent.Source source, boolean withWarning) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.source = source;
        this.withWarning = withWarning;
    }

    /**
     * 包装：内容进上下文之前加边界与来源行。
     *
     * @param delegate 原工具
     * @param source 来源标签
     * @param withWarning 是否带边界提示句。web_search 不带（与 OpenClaw 一致），其余带
     * @return 包装过的工具
     */
    public static ToolCallback wrapping(ToolCallback delegate, ExternalContent.Source source,
                                        boolean withWarning) {
        return new UntrustedToolCallback(delegate, Objects.requireNonNull(source, "source"), withWarning);
    }

    /**
     * 只净化特殊 token，不贴来源。
     *
     * @param delegate 原工具
     * @return 净化过的工具
     */
    public static ToolCallback sanitizing(ToolCallback delegate) {
        return new Sanitizing(delegate);
    }

    /**
     * 穿透这一层装饰器，拿到里面那个工具（票 19）。
     *
     * <p>给收集侧用：判断"这份清单是不是已经被包过"，必须能穿过装饰器看懂里面。
     * 只看最外层的类型会漏——{@code Sanitizing(MeteredToolCallback(…))} 的最外层是净化器，
     * 收集侧于是又包一层计量，同一次调用就写两遍审计。
     *
     * @param callback 任意工具回调
     * @return 里面的那个；这一层不是装饰器时返回 {@code null}
     */
    public static ToolCallback unwrap(ToolCallback callback) {
        if (callback instanceof Sanitizing sanitizing) {
            return sanitizing.delegate;
        }
        if (callback instanceof UntrustedToolCallback untrusted) {
            return untrusted.delegate;
        }
        return null;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return wrap(delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return wrap(delegate.call(toolInput, toolContext));
    }

    private String wrap(String result) {
        if (result == null || result.isEmpty()) {
            return result;
        }
        List<String> hits = ExternalContent.detect(result);
        if (!hits.isEmpty()) {
            // 只记，不改：子串匹配必被绕过，阻断只会让正常内容莫名失效。
            log.debug("外部内容里出现了可疑模式：source={} 命中={}",
                    source.name().toLowerCase(Locale.ROOT), hits);
        }
        return ExternalContent.wrap(result, source, withWarning);
    }

    /** 只净化特殊 token 的那一支。没有来源，也没有指标——它不该改变任何语义。 */
    private static final class Sanitizing implements ToolCallback {

        private final ToolCallback delegate;

        private Sanitizing(ToolCallback delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return clean(delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return clean(delegate.call(toolInput, toolContext));
        }

        private String clean(String result) {
            if (result == null || result.isEmpty() || ExternalContent.isWrapped(result)) {
                return result;
            }
            return ExternalContent.sanitize(result);
        }
    }
}
