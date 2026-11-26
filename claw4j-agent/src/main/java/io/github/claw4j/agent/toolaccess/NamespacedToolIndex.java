package io.github.claw4j.agent.toolaccess;

import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 给任意索引加"按组收窄"这一层（框架的 {@code categoryFilter}）。
 *
 * <p>为什么是包装而不是自己实现一个索引：{@link ToolIndex} 只有四个方法，而"怎么把 query 变成
 * 候选"这件事每一档都不一样（正则 / Lucene / 向量）。分组是横切的一层，跟哪一档无关——写成一个
 * 包装，换索引时不用重写它。
 *
 * <p>为什么框架那一层不够：{@code ToolSearchRequest#categoryFilter} 已经作为
 * {@code @ToolParam} 交给模型了（{@code ToolSearchTool}:51），但三个现成索引都没读它，
 * 所以模型传了等于没传，而且看不出来——它只会觉得"我收窄了，结果还是那些"。
 *
 * <p><b>先放大再过滤</b>：内层只取 {@code maxResults} 条的话，过滤掉别的组之后就凑不满一页，
 * 表现是"收窄之后能用的工具更少了"。所以先按 {@value #WIDEN} 倍取，过滤后再截回原数。
 */
public class NamespacedToolIndex implements ToolIndex {

    /** 过滤会打折，先多取几倍。3 倍是够用的：同组工具通常只占候选的零头。 */
    private static final int WIDEN = 3;

    private final ToolIndex delegate;

    private final ToolCategories categories;

    /** 每个会话这一轮有哪些工具、各属于哪组。会话之间工具集可以不同（档位、人设），不能共用一份。 */
    private final Map<String, Map<String, String>> bySession = new ConcurrentHashMap<>();

    /**
     * @param delegate   真正检索的那一档
     * @param categories 分组规则
     */
    public NamespacedToolIndex(ToolIndex delegate, ToolCategories categories) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.categories = Objects.requireNonNull(categories, "categories");
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
        String wanted = toolSearchRequest.categoryFilter();
        if (wanted == null || wanted.isBlank()) {
            return this.delegate.search(toolSearchRequest);
        }
        Map<String, String> groups = this.bySession.getOrDefault(toolSearchRequest.sessionId(), Map.of());
        if (groups.isEmpty()) {
            // 没有分组信息（会话刚开始、或索引被别人清过）时按"收窄不到"处理，而不是返回空：
            // 空结果会让模型断定"这个组里没有工具"，而事实是"我还不知道谁属于哪个组"。
            return this.delegate.search(toolSearchRequest);
        }

        int requested = toolSearchRequest.maxResults() == null
                ? ToolSearchProperties.DEFAULT_MAX_RESULTS : toolSearchRequest.maxResults();
        ToolSearchRequest widened = new ToolSearchRequest(toolSearchRequest.sessionId(),
                toolSearchRequest.query(), requested * WIDEN, null);

        List<ToolReference> matches = new ArrayList<>();
        for (ToolReference reference : this.delegate.search(widened).toolReferences()) {
            if (wanted.equals(groups.get(reference.toolName())) && matches.size() < requested) {
                matches.add(reference);
            }
        }
        return ToolSearchResponse.builder().toolReferences(matches).totalMatches(matches.size()).build();
    }

    /**
     * 里面真正检索的那一档。
     *
     * <p>给启动日志与测试用：装配摸得着的是包装，而"到底选了正则还是向量"只在这里。
     *
     * @return 被包装的索引
     */
    public ToolIndex delegate() {
        return this.delegate;
    }

    @Override
    public void clearIndex(String sessionId) {
        this.delegate.clearIndex(sessionId);
        this.bySession.remove(sessionId);
    }

    private void remember(String sessionId, ToolReference reference) {
        if (sessionId == null || reference == null || reference.toolName() == null) {
            return;
        }
        String category = this.categories.categoryOf(reference.toolName());
        if (category.isEmpty()) {
            return;
        }
        this.bySession.computeIfAbsent(sessionId, key -> new ConcurrentHashMap<>())
                .put(reference.toolName(), category);
    }
}
