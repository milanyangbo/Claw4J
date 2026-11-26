package io.github.claw4j.agent.toolaccess;

import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;

import java.util.List;
import java.util.Objects;

/**
 * 只给向量那一路换描述：入库时用「中文 + 原文」，检索时原样转发（票 11 修正）。
 *
 * <p>为什么不再统一包在回调出口：2026-11-26 的开/关对照量出来，中文化让向量那一路明显受益
 * （hybrid-0.0 的 zh-en 档 R@1 0.60 → 0.80），却让**纯关键字那一路下降**（regex 0.15 → 0.10）——
 * 描述变长，中文二元组可碰撞的字变多，无关工具更容易挤进前排。既然收益与代价分在两条路上，
 * 就不该让两条路读同一份文本。
 *
 * <p>包在这里而不是包在 {@code HybridToolIndex} 里：单独用 {@code index=vector} 的部署同样需要它，
 * 而 hybrid 只是"关键字 + 一个向量索引"。位置选在向量索引外面一层，两档自动都覆盖。
 *
 * <p>模型看到的描述保持原文：它读英文没有障碍，而中文那一份的唯一用处是被 embed 成向量。
 * 这也不违背"模型与索引同源"——同源的是**工具名与能力**，不是逐字相同；真正要防的是
 * "搜出来的工具在清单里找不到"，那是名字的事。
 */
public final class TranslatedVectorIndex implements ToolIndex {

    private final ToolIndex delegate;

    private final ToolDescriptionTranslator translator;

    /**
     * @param delegate   真的向量索引
     * @param translator 翻译器；{@link ToolDescriptionTranslator#NONE} 时整个让位、不做复制
     */
    public TranslatedVectorIndex(ToolIndex delegate, ToolDescriptionTranslator translator) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.translator = Objects.requireNonNull(translator, "translator");
    }

    @Override
    public void indexTool(String sessionId, ToolReference toolReference) {
        this.delegate.indexTool(sessionId,
                this.translator.translateReferences(List.of(toolReference)).getFirst());
    }

    @Override
    public void indexTools(String sessionId, List<ToolReference> toolReferences) {
        this.delegate.indexTools(sessionId, this.translator.translateReferences(toolReferences));
    }

    @Override
    public void clearIndex(String sessionId) {
        this.delegate.clearIndex(sessionId);
    }

    @Override
    public ToolSearchResponse search(ToolSearchRequest toolSearchRequest) {
        return this.delegate.search(toolSearchRequest);
    }

    /** @return 里面那个向量索引，给装配断言与排障用 */
    public ToolIndex delegate() {
        return this.delegate;
    }
}
