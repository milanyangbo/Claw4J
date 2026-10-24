package io.github.claw4j.app;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 金额断言：一个数在真模型的回答里有四种写法，断言要认全。
 *
 * <p>真模型会自己决定加不加千分位、带不带小数——同一个 128400 可能被写成
 * {@code 128,400.00} / {@code 128400.00} / {@code 128,400} / {@code 128400}。
 * 只认一种写法，断言就会在措辞变化上假红，而我们要抓的是"拿到数 / 没拿到数"，
 * 不是"模型怎么排版"。
 *
 * <p>与 agent 侧 {@code RealModelSupport.amountForms} 是同一套写法，这里是第二份。
 * 两份不合并的原因与数据库夹具那次一样：合并要靠 test-jar，而那会让
 * {@code ./mvnw test}（早于 package）在 app 解析失败。为消重复去破坏常用命令不划算，
 * 所以留两份、并在这里写清"改一处要改两处"。
 */
public final class Amounts {

    private Amounts() {
    }

    /** 回答里应当出现这个数（任意一种写法）。 */
    public static void assertMentions(String answer, String digits) {
        List<String> forms = forms(digits);
        assertThat(forms.stream().anyMatch(answer::contains))
                .as("回答里应当出现 %s 的某种写法 %s，实际拿到的是：%s", digits, forms, answer)
                .isTrue();
    }

    /** 回答里不该出现这个数（任何一种写法都不行）。 */
    public static void assertOmits(String answer, String digits) {
        List<String> forms = forms(digits);
        assertThat(forms.stream().noneMatch(answer::contains))
                .as("回答里不该出现 %s 的任何写法 %s，实际拿到的是：%s", digits, forms, answer)
                .isTrue();
    }

    /** 加千分位、加小数、都不加 —— 与 agent 侧同一套。 */
    public static List<String> forms(String digits) {
        String grouped = String.format("%,d", Long.parseLong(digits));
        return List.of(grouped + ".00", digits + ".00", grouped, digits);
    }
}
