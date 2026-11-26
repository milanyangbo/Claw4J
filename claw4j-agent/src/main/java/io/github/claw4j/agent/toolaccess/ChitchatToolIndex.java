package io.github.claw4j.agent.toolaccess;

import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 寒暄与空查询不给候选：那不是找工具，是人在说话。
 *
 * <p>为什么单独一层：检索档位只回答"怎么把 query 变成候选"，它们都没有"这句话根本不该检索"这个
 * 判断。向量那一路尤其危险——它对任何输入都会按相似度交回前 k 个，于是「谢谢」也能拿到五个工具，
 * 模型顺手就调一个。这不是检索准不准的问题，是它压根不该被问。
 *
 * <p>2026-11-26 之前这条规则长在自研的二元组索引里，只对 {@code index=regex} 生效；而那时
 * {@code application.yml} 的默认已经是 {@code hybrid}，评测里"零候选率"那一列也仍然量的
 * {@code regex}——**这一列从没描述过生产**。删掉那一档时它才露出来：生产对十道寒暄题全部给了
 * 五个候选。所以它必须独立成层，与索引档位无关。
 *
 * <p>判据是<b>整句只由寒暄词拼成</b>，删空才算命中。所以它只增不减：任何一句带具体内容的话
 * 都走原路，不会因为这条规则少一个候选。刻意不用"短句 + 无动词就判闲聊"那种更通用的规则——
 * 实测它会误杀「那个进程打印了什么」（要 BashOutput）、「还有什么事挂着没人管」（要
 * github_issue_list），而那两句子正是检索该处理的情况。
 *
 * <p>空 query 一并挡掉：框架的 {@code RegexToolIndex} 在空 query 上返回 {@code .*}，
 * 向量那一路会交回前 k 个——两种表现都是"模型随便发个空查询就把渐进式披露静默关掉"。
 */
public final class ChitchatToolIndex implements ToolIndex {

    /**
     * 寒暄与语气词。
     *
     * <p>词表每一条都能指到 {@code eval/tools/retrieval/gold.yaml} 里一道 no-search 题；
     * 单字那几条是为了让「好的吧」「嗯…那」这类组合也能被整体吃掉。
     */
    private static final List<String> CHITCHAT = List.of(
            "谢谢", "感谢", "多谢", "辛苦", "好的", "收到", "知道", "明白", "了解", "哈哈", "呵呵",
            "再见", "回头聊", "先这样", "就这样", "那就这样", "你在吗", "在吗", "今天", "天气",
            "不错", "没事", "不用", "好", "嗯", "哦", "行", "吧", "了", "的", "那", "你", "吗", "啊", "哈");

    /** 长词先删：先吃掉「那就这样」，剩下的「那」才不会把后半句也一起吞掉。 */
    private static final List<String> LONGEST_FIRST = CHITCHAT.stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    private final ToolIndex delegate;

    /**
     * @param delegate 内层索引，任意一档
     */
    public ChitchatToolIndex(ToolIndex delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
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
        if (isSmallTalk(toolSearchRequest.query())) {
            return ToolSearchResponse.builder()
                    .toolReferences(List.of())
                    .totalMatches(0)
                    .searchMetadata(ToolSearchResponse.SearchMetadata.builder()
                            .searchType(getClass().getSimpleName())
                            .query(toolSearchRequest.query())
                            .build())
                    .build();
        }
        return this.delegate.search(toolSearchRequest);
    }

    @Override
    public void clearIndex(String sessionId) {
        this.delegate.clearIndex(sessionId);
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
     * 整句是不是寒暄、或者压根没内容。
     *
     * <p>先去掉标点空白（「谢谢，明白了」里的逗号不该让判定失效），再把词表按长度从长到短逐个
     * 子串删除，删空即视为寒暄。用子串删除而不是分词：这些词本来就没有歧义边界，
     * 「那就这样」与「那」的先后顺序才是唯一要照顾的事。
     *
     * @param query 模型这一轮写的检索词
     * @return 该跳过检索时为真
     */
    private static boolean isSmallTalk(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String rest = query.replaceAll("[^\\p{L}\\p{N}]", "");
        if (rest.isEmpty()) {
            return true;
        }
        for (String word : LONGEST_FIRST) {
            rest = rest.replace(word, "");
        }
        return rest.isEmpty();
    }
}
