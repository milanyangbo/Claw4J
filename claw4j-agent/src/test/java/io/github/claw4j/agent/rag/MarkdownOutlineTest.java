package io.github.claw4j.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 章节抽取的单测。
 *
 * <p>抽错的后果很轻（前缀写错一章）但很难发现：检索仍然返回结果，只是每段都带着别人的标题。
 * 所以每条规则都钉住，尤其是"下标算错会让整篇错位一章"这一条。
 */
class MarkdownOutlineTest {

    private static final String DOC = """
            # 售后政策

            开头一段。

            ## 第二章 退货

            ### 2.3 运费承担

            无理由退货的运费由买家承担。

            ## 第三章 换货

            换货免运费。
            """;

    @Test
    @DisplayName("标题取第一个一级标题，不取章节标题")
    void titleIsTheFirstH1() {
        assertThat(MarkdownOutline.title(DOC, "after-sales.md")).isEqualTo("售后政策");
    }

    @Test
    @DisplayName("没有一级标题就用文件名：空标题会让前缀变成「【 › 第二章】」")
    void titleFallsBackToFileName() {
        String content = "## 只有二级\n\n内容。";

        assertThat(MarkdownOutline.title(content, "warranty-terms.md")).isEqualTo("warranty terms");
    }

    @Test
    @DisplayName("章节路径带上级：三级标题的完整路径是「第二章 退货 › 2.3 运费承担」")
    void nestedHeadingsKeepTheirAncestors() {
        List<MarkdownOutline.Section> sections = MarkdownOutline.sections(DOC);
        int feeOffset = DOC.indexOf("### 2.3 运费承担");

        assertThat(MarkdownOutline.at(sections, feeOffset))
                .isEqualTo("第二章 退货 › 2.3 运费承担");
        assertThat(MarkdownOutline.at(sections, DOC.indexOf("无理由退货的运费由买家承担")))
                .as("正文属于它上面最近的那个标题")
                .isEqualTo("第二章 退货 › 2.3 运费承担");
    }

    @Test
    @DisplayName("同级标题替换而不叠加：第三章下面不该还带着 2.3")
    void siblingHeadingReplacesNotAppends() {
        List<MarkdownOutline.Section> sections = MarkdownOutline.sections(DOC);

        assertThat(MarkdownOutline.at(sections, DOC.indexOf("换货免运费")))
                .isEqualTo("第三章 换货");
    }

    @Test
    @DisplayName("第一条标题之前没有章节：返回空串，前缀只留文档标题")
    void beforeFirstHeadingHasNoSection() {
        List<MarkdownOutline.Section> sections = MarkdownOutline.sections(
                "前言没有标题。\n\n# 第一章\n\n正文。");

        assertThat(MarkdownOutline.at(sections, 0)).isEmpty();
        assertThat(MarkdownOutline.prefix("某文档", "")).isEqualTo("【某文档】\n");
    }

    @Test
    @DisplayName("围栏代码块里的 # 不算标题：一段 shell 注释会把整篇的章节改成命令名")
    void hashInsideCodeFenceIsNotAHeading() {
        String content = """
                # 部署说明

                ```
                # 装依赖
                ## 起服务
                ```

                ## 回滚

                重跑上一个版本。
                """;

        List<MarkdownOutline.Section> sections = MarkdownOutline.sections(content);

        assertThat(sections).extracting(MarkdownOutline.Section::path)
                .as("一级标题只是文档标题，不算章节位置")
                .containsExactly("回滚");
        assertThat(MarkdownOutline.at(sections, content.indexOf("重跑上一个版本")))
                .isEqualTo("回滚");
    }

    @Test
    @DisplayName("块文本被拼接、换行被删掉时，靠块里出现的标题文字定位")
    void sectionIsFoundByHeadingTextWhenOffsetsDoNotMatch() {
        List<MarkdownOutline.Section> sections = MarkdownOutline.sections(DOC);
        // 切块器输出的是这种形态：句子被拼接起来，原文里的换行没了。
        String glued = "# 售后政策## 第二章 退货七天无理由退货，运费由买家承担。";

        assertThat(MarkdownOutline.sectionOf(sections, glued, -1))
                .as("按位置找必然失败（这不是原文子串），但块里带着「第二章 退货」这几个字；"
                        + "块里没有 2.3 的标题，所以停在第二章")
                .isEqualTo("第二章 退货");
    }

    @Test
    @DisplayName("块里一个标题都没有时回到块起点所属章节")
    void sectionFallsBackToOffset() {
        List<MarkdownOutline.Section> sections = MarkdownOutline.sections(DOC);
        int body = DOC.indexOf("换货免运费");

        assertThat(MarkdownOutline.sectionOf(sections, "换货免运费", body)).isEqualTo("第三章 换货");
    }

    @Test
    @DisplayName("前缀有长度上限：章节标题是整句时不能把正文挤掉")
    void prefixIsTruncated() {
        String prefix = MarkdownOutline.prefix("文档", "很长的章节名".repeat(30));

        assertThat(prefix.length()).isLessThanOrEqualTo(MarkdownOutline.MAX_PREFIX_CHARS + 1);
        assertThat(prefix).endsWith("】\n");
    }

    @Test
    @DisplayName("章节链最多三级：更深的层级已经是条目，不是章节")
    void pathKeepsAtMostThreeLevels() {
        String content = """
                # 一
                ## 二
                ### 三
                #### 四

                正文。
                """;

        assertThat(MarkdownOutline.at(MarkdownOutline.sections(content), content.indexOf("正文")))
                .isEqualTo("二 › 三 › 四");
    }
}
