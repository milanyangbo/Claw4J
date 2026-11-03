package io.github.claw4j.agent.rag;

import java.util.List;

/**
 * 按问题找文档片段。**它不是缓存**：每次调用都真的去查，结果带出处。
 *
 * <p>与 {@code SemanticCache} 的区别不在实现，在语义：
 * 那边问的是"这个问题是不是问过"（命中就返回历史回答），这边问的是"哪几段文档相关"
 * （每次都要带上"来自哪份文档的第几段"）。所以两张表、两套阈值、两个接口——
 * 合在一起的最坏情况是把一段文档当成"有人问过"的答案吐出去，那句"而不是把某段文档当成
 * 有人问过的答案"说的就是这件事。
 *
 * <p>这一版只有 {@link #search} 一个方法：不提供"按 id 取"、不提供"写入"。
 * 写入是索引器的事（模型不该往知识库里写，那是给它一个把提示注入落库的口子）。
 */
public interface KnowledgeBase {

    /**
     * 一次检索的结果。
     *
     * @param source  相对目录的路径，出处的第一段
     * @param ordinal 文档内第几块，从 1 开始，出处的第二段
     * @param text    原文片段
     * @param score   融合后的分数。**只用于排序，不要展示给模型**——
     *                它是 RRF 的值（两个通道的排名倒数之和），没有"相似度"的含义
     */
    record Hit(String source, int ordinal, String text, double score) {

        /** 出处的形状：{@code 文档路径 第 N 段}。拼接只该有一处，否则两处会写出两种格式。 */
        public String citation() {
            return source + " 第 " + ordinal + " 段";
        }
    }

    /**
     * 检索。
     *
     * @param query 用户的问题原文。内部切 bigram，不需要调用方预处理
     * @return 按相关度降序；没有命中时返回空列表（不是 null）
     */
    List<Hit> search(String query);

    /** 库里有多少块。用在"没检索到"的回话里——让"没有这段"与"库是空的"分得开。 */
    int chunkCount();
}
