package io.github.claw4j.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 词项切分的单测。
 *
 * <p>只测"这条查询会拿哪些子串去 {@code ilike}"，不连库：SQL 的形状与端到端命中在
 * {@code SearchSqlTest} 里（那边起真容器）。
 *
 * <p>这一层的错都很安静：词项少了，检索只是变差；词项多了，SQL 只是变长；跨标点拼出一个
 * 不存在的组合，那个 {@code or} 只是永远不命中。所以每条都得钉住。
 */
class KeywordDocumentRetrieverTest {

    @Test
    @DisplayName("中文长问题切成 bigram：整句在文档里不会原样出现，靠两字组合去命中")
    void longChineseQueryBecomesBigrams() {
        List<String> terms = KeywordDocumentRetriever.searchTerms("为什么隔壁那家店能退我不能退");

        assertThat(terms)
                .as("整句十七个字，加进去只是一条必然不命中的 or")
                .doesNotContain("为什么隔壁那家店能退我不能退")
                .contains("隔壁", "能退", "不能");
        assertThat(terms).allSatisfy(term -> assertThat(term).hasSize(2));
    }

    @Test
    @DisplayName("短查询保留整句：它本身就是最好的词项")
    void shortQueryKeepsWholeText() {
        List<String> terms = KeywordDocumentRetriever.searchTerms("退货政策");

        assertThat(terms).startsWith("退货政策");
        assertThat(terms).contains("退货", "政策");
    }

    @Test
    @DisplayName("型号锚点排在 bigram 之前：长提问里它不能被 bigram 挤出上限")
    void anchorComesBeforeBigrams() {
        String query = "请问仓库里那个 SKU-2024 现在还剩多少件可以发货的库存呢";

        List<String> terms = KeywordDocumentRetriever.searchTerms(query);

        assertThat(terms).contains("SKU-2024");
        assertThat(terms.indexOf("SKU-2024"))
                .as("锚点是最强的字面信号，被 bigram 挤掉就白搭")
                .isLessThan(3);
    }

    @Test
    @DisplayName("两位型号也是锚点：`A2` 是价格表那块唯一能匹配的字面")
    void twoCharacterModelIsAnAnchor() {
        assertThat(KeywordDocumentRetriever.searchTerms("A2 买一年延保要多少钱"))
                .as("≥3 字符的锚点规则会把 A2 漏掉，价格表第 1 块就永远进不了候选")
                .contains("A2");
        assertThat(KeywordDocumentRetriever.searchTerms("用一台外观良好的 A1 以旧换新能抵多少钱"))
                .contains("A1");
    }

    @Test
    @DisplayName("词项数有上限：一条长提问不能变成上千字符的 SQL")
    void termCountIsCapped() {
        String query = "华东区售后政策里关于备机押金退还流程与违约金计算方式的全部规定是什么".repeat(3);

        assertThat(KeywordDocumentRetriever.searchTerms(query)).hasSizeLessThanOrEqualTo(12);
    }

    @Test
    @DisplayName("百分号只走整句：它既不匹配 ASCII 锚点，也不含中文")
    void percentSignKeepsOnlyWholeText() {
        assertThat(KeywordDocumentRetriever.searchTerms("5%")).containsExactly("5%");
    }

    @Test
    @DisplayName("bigram 不跨标点：跨了就会拼出文档里不存在的组合")
    void bigramsDoNotCrossPunctuation() {
        List<String> terms = KeywordDocumentRetriever.searchTerms("能退货，不能换货");

        assertThat(terms).contains("退货", "换货");
        assertThat(terms)
                .as("「货不」横跨了逗号，文档里不会出现")
                .doesNotContain("货不");
    }

    @Test
    @DisplayName("空查询没有词项：空串会让 SQL 变成必然命中的 or")
    void blankQueryHasNoTerms() {
        assertThat(KeywordDocumentRetriever.searchTerms("   ")).isEmpty();
        assertThat(KeywordDocumentRetriever.searchTerms(null)).isEmpty();
    }
}
