package io.github.claw4j.agent.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;

import java.util.List;
import java.util.Objects;

/**
 * 检索的计量：搜了几次、搜到没有、每次回来几个（票 08）。
 *
 * <p>为什么不复用 {@code claw4j.tool.calls}：那个指标回答的是"哪个工具被问得多"，它决定下一个
 * 该优化谁、哪个该收权限。检索工具不是业务工具，它是"找工具"这个动作本身——混进去之后排行第一的
 * 永远是它，而它一个业务动作都没做。同理不进 {@code tool_action} 审计：审计回答"谁干了什么"，
 * 检索不是动作，是找动作的手段。
 *
 * <p>为什么包在索引这一层而不是包 {@code ToolSearchTool} 的回调：索引是<b>每个检索都要过一遍</b>的
 * 那一处，与谁调它无关（今天只有工具循环调用，明天可能有别处）。包在回调上就只能在"从工具循环
 * 进来"的那条路上看见它。
 *
 * <p>{@code narrowed} 是这套指标里最有用的一格，它把两种"空手"分开：收窄之后空手说明分组配错了、
 * 或者模型在乱填 {@code categoryFilter}；没收窄就空手说明是索引与工具描述的问题。
 *
 * <p>没有 {@link MeterRegistry} 时照常转发、不记：与 {@link MeteredToolCallback} 同一条口径——
 * 装不装 actuator 只影响报不报得出去，不影响调用本身。
 */
public class MeteredToolIndex implements ToolIndex {

    /** 检索次数，按结果与"有没有收窄"分。 */
    public static final String SEARCH_CALLS = "claw4j.tool.search.calls";

    /** 每次检索返回几个候选。答的是"经常空手"还是"经常顶满 max-results"。 */
    public static final String SEARCH_RESULTS = "claw4j.tool.search.results";

    /** 返回了非空的候选。 */
    public static final String HIT = "hit";

    /** 索引返回空。注意它不等于"模型没用上"——后者是另一件事（见票 08 的缺口）。 */
    public static final String MISS = "miss";

    /** 索引自己抛了异常。 */
    public static final String ERROR = "error";

    private final ToolIndex delegate;

    private final MeterRegistry registry;

    /**
     * @param delegate 真正检索的那一档，必填
     * @param registry 指标注册表；为 {@code null} 表示没装 actuator，照常转发只是不记
     */
    public MeteredToolIndex(ToolIndex delegate, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registry = registry;
    }

    @Override
    public void indexTool(String sessionId, ToolReference toolReference) {
        this.delegate.indexTool(sessionId, toolReference);
    }

    @Override
    public void indexTools(String sessionId, List<ToolReference> toolReferences) {
        this.delegate.indexTools(sessionId, toolReferences);
    }

    @Override
    public ToolSearchResponse search(ToolSearchRequest toolSearchRequest) {
        boolean narrowed = toolSearchRequest.categoryFilter() != null
                && !toolSearchRequest.categoryFilter().isBlank();
        try {
            ToolSearchResponse response = this.delegate.search(toolSearchRequest);
            int hits = response == null || response.toolReferences() == null
                    ? 0 : response.toolReferences().size();
            count(hits == 0 ? MISS : HIT, narrowed);
            recordResults(hits);
            return response;
        } catch (RuntimeException failure) {
            // 异常不吞：模型那一侧收到的仍是工具错误，计量只是顺手记一笔。
            // 记成 error 而不是 miss——"没搜到"与"搜崩了"的修法完全不同。
            count(ERROR, narrowed);
            throw failure;
        }
    }

    @Override
    public void clearIndex(String sessionId) {
        this.delegate.clearIndex(sessionId);
    }

    /**
     * 里面真正检索的那一档。
     *
     * <p>给启动日志与测试用：装配摸得着的是最外层，而"到底选了正则还是向量、有没有分组"只在这里。
     *
     * @return 被包装的索引
     */
    public ToolIndex delegate() {
        return this.delegate;
    }

    private void count(String result, boolean narrowed) {
        if (this.registry == null) {
            return;
        }
        Counter.builder(SEARCH_CALLS)
                .description("工具检索次数")
                .tag("result", result)
                .tag("narrowed", Boolean.toString(narrowed))
                .register(this.registry)
                .increment();
    }

    private void recordResults(int hits) {
        if (this.registry == null) {
            return;
        }
        DistributionSummary.builder(SEARCH_RESULTS)
                .description("每次检索返回的候选工具个数")
                .register(this.registry)
                .record(hits);
    }
}
