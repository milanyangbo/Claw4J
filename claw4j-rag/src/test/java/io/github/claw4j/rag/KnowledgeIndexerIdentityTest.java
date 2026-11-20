package io.github.claw4j.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 索引身份的单测。
 *
 * <p>身份是"库里这批向量是谁、按什么边界算出来的"唯一记录。它少一项，换模型就不会重建，
 * 新旧向量混着用，而症状只是"分数还是正常的"——没有任何报错可查。所以每一项都单独钉一条。
 */
class KnowledgeIndexerIdentityTest {

    @Test
    @DisplayName("换向量模型，身份就变：否则换完模型库里新旧向量混用，分数看着正常")
    void modelChangeInvalidatesIdentity() {
        assertThat(KnowledgeIndexer.identityOf(800, "text-embedding-v4", 1024))
                .isNotEqualTo(KnowledgeIndexer.identityOf(800, "text-embedding-v3", 1024));
    }

    @Test
    @DisplayName("换维度，身份也变：维度不同时插入会失败，混用不会，所以两种都要写进身份")
    void dimensionChangeInvalidatesIdentity() {
        assertThat(KnowledgeIndexer.identityOf(800, "text-embedding-v4", 1024))
                .isNotEqualTo(KnowledgeIndexer.identityOf(800, "text-embedding-v4", 768));
    }

    @Test
    @DisplayName("改块长，身份变：它同样改变块边界")
    void chunkSizeChangeInvalidatesIdentity() {
        assertThat(KnowledgeIndexer.identityOf(800, "text-embedding-v4", 1024))
                .isNotEqualTo(KnowledgeIndexer.identityOf(400, "text-embedding-v4", 1024));
    }

    @Test
    @DisplayName("同样的输入永远同一个串：它是按字符串比较的，抖一下就是一次全量重建")
    void identityIsStable() {
        String first = KnowledgeIndexer.identityOf(800, "text-embedding-v4", 1024);
        String second = KnowledgeIndexer.identityOf(800, "text-embedding-v4", 1024);

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("身份里能看到模型名与维度：全量重建的日志要靠它回答「这次为什么重建」")
    void identityCarriesTheModel() {
        assertThat(KnowledgeIndexer.identityOf(800, "text-embedding-v4", 1024))
                .contains("\"embedding\":\"text-embedding-v4\"")
                .contains("\"dimensions\":1024")
                .contains("\"chunkTokens\":800");
    }
}
