package io.github.claw4j.rag;

import io.github.claw4j.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回答"用法"那一段的单测。
 *
 * <p>要守三件事：默认带范围约束（只答被问到的部分）、关掉能退回旧三条（否则对照实验做不了）、
 * 自定义不能把模板占位符切开。第三条是真正的坑——花括号混进"用法"里，报错不会出现在构建期，
 * 而是变成"问题被吃掉一段"，那时候没人会想到是提示词。
 */
class AnswerUsageTest {

    private static String template(RagProperties.Answer answer) {
        return RagAutoConfiguration.promptTemplate(answer);
    }

    @Test
    @DisplayName("默认：带范围约束，且两个占位符都在")
    void defaultCarriesScopeRules() {
        String prompt = template(RagProperties.defaults().answer());

        assertThat(prompt)
                .contains("用法：\n1. 资料确实回答了问题")
                .contains("2. 只答被问到的部分")
                .contains("3. 一个问题要几处资料合起来才答得全")
                .contains("4. 篇幅按问题定")
                .contains("{context}")
                .contains("{query}");
    }

    @Test
    @DisplayName("默认：规则与问题之间只有一个空行，拼接不会多空行")
    void defaultKeepsSingleBlankLine() {
        String prompt = template(RagProperties.defaults().answer());

        assertThat(prompt).contains("6. 不要在资料之外编造规定、数字或流程。\n\n问题：{query}");
    }

    @Test
    @DisplayName("关掉：退回旧三条，范围约束消失——没有这一版就做不了对照")
    void disabledFallsBackToLegacyRules() {
        String prompt = template(new RagProperties.Answer(false, null));

        assertThat(prompt)
                .doesNotContain("只答被问到的部分")
                .doesNotContain("合起来答")
                .contains("2. 资料与问题无关，就忽略它")
                .contains("{query}");
    }

    @Test
    @DisplayName("自定义：整段替换，内置规则一条都不留")
    void customInstructionsReplaceDefaults() {
        String prompt = template(new RagProperties.Answer(true, "只输出一句话。"));

        assertThat(prompt)
                .contains("用法：\n只输出一句话。")
                .doesNotContain("只答被问到的部分")
                .contains("{context}")
                .contains("{query}");
    }

    @Test
    @DisplayName("自定义含花括号：启动即抛，不留到运行期")
    void bracesInInstructionsFailFast() {
        assertThatThrownBy(() -> template(new RagProperties.Answer(true, "按 {context} 回答")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("answer.instructions");
    }

    @Test
    @DisplayName("配置缺失时默认开、措辞为 null（用内置）")
    void propertiesDefaultToEnabledBuiltin() {
        RagProperties.Answer answer = RagProperties.defaults().answer();

        assertThat(answer.enabled()).isTrue();
        assertThat(answer.instructions()).isNull();
    }

    @Test
    @DisplayName("空白字符串等同没配，不当成自定义")
    void blankInstructionsAreTreatedAsAbsent() {
        assertThat(new RagProperties.Answer(null, "   ").instructions()).isNull();
    }
}
