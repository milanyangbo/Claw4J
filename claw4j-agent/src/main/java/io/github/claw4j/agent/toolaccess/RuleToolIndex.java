package io.github.claw4j.agent.toolaccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 人写的正则规则层：query 命中一条规则，就把规则点名的工具排到候选最前面。
 *
 * <p>它补的是相似度做不到的那一类确定性：{@code 报销} 与 {@link ToolReference} 里任何一个工具名都
 * 没有字面重合、语义上也偏远，但业务上就该走那个导出工具。这种断言由人写死比让模型猜可靠，
 * 也让"为什么它排第一"有一个能指到配置的答案。
 *
 * <p><b>只是置顶，不参与融合</b>：{@link HybridToolIndex} 的 RRF 吃的是名次，规则命中是断言而不是
 * 相关性分数，两者不是一个量纲。混进去之后，一次规则命中和一次"碰巧两路都排前"在分数上分不开，
 * 排障时就得反推。所以顺序上它包在最外层，看到的已经是融合完、截断完的那一版。
 *
 * <p><b>不命中就原样透传</b>：没配规则、query 不命中、规则点名的工具在当前会话里不存在——这三种情况
 * 都返回内层那一个响应对象，不重建。规则层因此可以被证明是"只增不减"的：它只会把某个工具提前，
 * 不会让任何一个候选消失（除了被 {@code maxResults} 截断的尾部）。
 *
 * <p>正则语义就是 {@link java.util.regex.Pattern} 那一套，与框架 {@code RegexToolIndex} 同一个引擎：
 * 匹配的是<b>整句 query 里有没有</b>（{@code find}），不是整句等于。所以 {@code 报销|发票|开票}
 * 一条就够，不用为每种说法写一条。写错的正则在构造时就抛——启动失败好过上线后静默不命中。
 */
public final class RuleToolIndex implements ToolIndex {

    private static final Logger log = LoggerFactory.getLogger(RuleToolIndex.class);

    /** 规则列表只在这里编译一次：配置变了要重启，没有热更新的需求。 */
    private final List<CompiledRule> rules;

    private final ToolIndex delegate;

    private final ToolCategories categories;

    /**
     * 每个会话这一轮有哪些工具，按名字留一份引用。
     *
     * <p>必须自己留：规则点名的工具可能压根没被内层检索出来（那正是规则存在的理由），
     * 拿不到 {@link ToolReference} 就没法把它塞进结果——{@code ToolReference} 要求 summary 非空。
     */
    private final Map<String, Map<String, ToolReference>> bySession = new ConcurrentHashMap<>();

    /**
     * 已经告警过的工具名，避免每轮都刷同一条。
     *
     * <p>规则里写错一个工具名是配置错误，但它不该让整轮对话失败：跳过它、留一行 WARN，
     * 剩下的规则照常生效。
     */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /**
     * @param delegate   内层索引（正则 / Lucene / 向量 / 融合任意一档）
     * @param rules      规则表，可以是空表（那时 {@link ToolSearch} 根本不会包这一层）
     * @param categories 分组规则，只用来判断规则点名的工具属不属于模型收窄的那一组
     * @throws IllegalStateException 某条规则的正则编译不过
     */
    public RuleToolIndex(ToolIndex delegate, List<ToolSearchProperties.Rule> rules, ToolCategories categories) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.categories = Objects.requireNonNull(categories, "categories");
        this.rules = compile(Objects.requireNonNull(rules, "rules"));
    }

    @Override
    public void indexTool(String sessionId, ToolReference toolReference) {
        this.delegate.indexTool(sessionId, toolReference);
        remember(sessionId, toolReference);
    }

    @Override
    public void indexTools(String sessionId, List<ToolReference> toolReferences) {
        this.delegate.indexTools(sessionId, toolReferences);
        toolReferences.forEach(reference -> remember(sessionId, reference));
    }

    @Override
    public ToolSearchResponse search(ToolSearchRequest toolSearchRequest) {
        ToolSearchResponse plain = this.delegate.search(toolSearchRequest);
        CompiledRule hit = match(toolSearchRequest.query());
        if (hit == null) {
            return plain;
        }

        List<ToolReference> promoted = promoted(toolSearchRequest, hit);
        if (promoted.isEmpty()) {
            return plain;
        }

        Set<String> seen = new LinkedHashSet<>();
        List<ToolReference> merged = new ArrayList<>(promoted);
        promoted.forEach(reference -> seen.add(reference.toolName()));
        for (ToolReference reference : plain.toolReferences()) {
            if (reference != null && reference.toolName() != null && seen.add(reference.toolName())) {
                merged.add(reference);
            }
        }

        int limit = toolSearchRequest.maxResults() == null
                ? ToolSearchProperties.DEFAULT_MAX_RESULTS : toolSearchRequest.maxResults();
        List<ToolReference> references = merged.size() > limit ? List.copyOf(merged.subList(0, limit)) : merged;
        if (log.isDebugEnabled()) {
            log.debug("工具检索规则命中：query={} 规则={} 置顶={} 最终={}", toolSearchRequest.query(),
                    hit.source(), hit.tools(), references.stream().map(ToolReference::toolName).toList());
        }

        return ToolSearchResponse.builder()
                .toolReferences(references)
                .totalMatches(references.size())
                .searchMetadata(plain.searchMetadata())
                .build();
    }

    @Override
    public void clearIndex(String sessionId) {
        this.delegate.clearIndex(sessionId);
        this.bySession.remove(sessionId);
    }

    /**
     * 内层真正检索的那一档，给装配断言用。
     *
     * @return 被包装的索引
     */
    public ToolIndex delegate() {
        return this.delegate;
    }

    /**
     * 第一条命中的规则，按配置顺序。
     *
     * <p>取第一条而不是把命中的全并起来：两条规则同时命中时，谁先谁后是配置作者写下的顺序，
     * 有了明确答案；合并则要再定义一个"规则之间怎么排"，而那件事没有更好的依据。
     *
     * @param query 模型这一轮写的检索词
     * @return 命中的规则；query 为空或一条都不命中时为 {@code null}
     */
    private CompiledRule match(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (CompiledRule rule : this.rules) {
            if (rule.pattern().matcher(query).find()) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 规则点名、且这一轮真的能用的工具，按规则里的顺序。
     *
     * <p>模型传了 {@code categoryFilter} 时按它过滤：那是模型明确的收窄，规则不该把它推翻。
     * 这一条与 {@link NamespacedToolIndex} 的过滤是同一个判据（{@link ToolCategories#categoryOf}）。
     *
     * @param request 这一轮的检索请求
     * @param rule    命中的规则
     * @return 可以置顶的工具引用；一个都用不上时为空
     */
    private List<ToolReference> promoted(ToolSearchRequest request, CompiledRule rule) {
        Map<String, ToolReference> available = this.bySession.getOrDefault(request.sessionId(), Map.of());
        String wanted = request.categoryFilter();
        boolean narrowed = wanted != null && !wanted.isBlank();
        List<ToolReference> promoted = new ArrayList<>();
        for (String name : rule.tools()) {
            ToolReference reference = available.get(name);
            if (reference == null) {
                if (this.warned.add(name)) {
                    log.warn("工具检索规则点名的工具不在这一轮的清单里：{}（规则 {}）。跳过它，其余规则照常生效。",
                            name, rule.source());
                }
                continue;
            }
            if (narrowed && !wanted.equals(this.categories.categoryOf(name))) {
                continue;
            }
            promoted.add(reference);
        }
        return promoted;
    }

    private void remember(String sessionId, ToolReference reference) {
        if (sessionId == null || reference == null || reference.toolName() == null) {
            return;
        }
        this.bySession.computeIfAbsent(sessionId, key -> new ConcurrentHashMap<>())
                .put(reference.toolName(), reference);
    }

    /**
     * 编译期就把正则编好。
     *
     * <p>放到检索时再编，{@code PatternSyntaxException} 会被框架 {@code RegexToolIndex#doSearch}
     * 的 catch 变成一次空结果，与"这个词确实没有对应工具"分不开。这里直接抛，
     * 坏掉的是启动，一眼能看见。
     *
     * @param rules 配置里的规则表
     * @return 编译好的规则，顺序不变
     * @throws IllegalStateException 某条规则的正则编译不过
     */
    private static List<CompiledRule> compile(List<ToolSearchProperties.Rule> rules) {
        List<CompiledRule> compiled = new ArrayList<>(rules.size());
        for (ToolSearchProperties.Rule rule : rules) {
            try {
                compiled.add(new CompiledRule(
                        Pattern.compile(rule.pattern(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                        rule.tools(), rule.pattern()));
            } catch (PatternSyntaxException e) {
                throw new IllegalStateException("claw4j.advisor.tool-search.rules 里这条正则写错了："
                        + rule.pattern() + "（" + e.getDescription() + "）", e);
            }
        }
        return List.copyOf(compiled);
    }

    /**
     * 一条编译好的规则。
     *
     * @param pattern 匹配整句 query 用的正则
     * @param tools   命中后要置顶的工具名，按配置顺序
     * @param source  配置里写的那串正则，只用于日志与异常消息
     */
    private record CompiledRule(Pattern pattern, List<String> tools, String source) {
    }
}
