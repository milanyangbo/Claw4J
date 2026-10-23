package io.github.claw4j.agent.advisor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiRedactorTest {

    private static final String PHONE = "13812345678";

    private static final String MASKED_PHONE = "138****5678";

    private static final String ID_CARD = "110101199003071234";

    private static final String MASKED_ID_CARD = "110101********1234";

    @Test
    void 手机号保留头三尾四() {
        assertThat(PiiRedactor.mask("联系他：" + PHONE + "。")).isEqualTo("联系他：" + MASKED_PHONE + "。");
    }

    @Test
    void 身份证保留头六尾四() {
        assertThat(PiiRedactor.mask("证件号 " + ID_CARD + " 已核验")).isEqualTo("证件号 " + MASKED_ID_CARD + " 已核验");
    }

    @Test
    void 身份证末位是X同样命中() {
        assertThat(PiiRedactor.mask(ID_CARD.substring(0, 17) + "X")).isEqualTo("110101********123X");
    }

    /** 反过来的一条：不是号码的数字不该被动——打码打错地方比不打更糟，因为它看着像真数据。 */
    @Test
    void 金额与订单号不被误伤() {
        assertThat(PiiRedactor.mask("上周成交额 128400.00 元，订单 202401010001 号"))
                .isEqualTo("上周成交额 128400.00 元，订单 202401010001 号");
    }

    @Test
    void 没有号码时原文照旧() {
        assertThat(PiiRedactor.mask("华东区上周成交额 128400.00 元")).isEqualTo("华东区上周成交额 128400.00 元");
        assertThat(PiiRedactor.countHits("华东区上周成交额 128400.00 元")).isZero();
    }

    /**
     * 流式可安全吐出的前缀：末尾还在"数字或 X"这一段里就先扣着。
     * 一旦出现别的字符（哪怕是句号），说明这个号码已经收尾了，前面那些可以定了。
     */
    @Test
    void 号码没吐完时整段扣住() {
        assertThat(PiiRedactor.safePrefix("138123")).isEmpty();
        assertThat(PiiRedactor.safePrefix("手机 138123")).isEqualTo("手机 ");
    }

    @Test
    void 号码收尾后前缀放行() {
        assertThat(PiiRedactor.safePrefix("手机 " + PHONE + "。")).isEqualTo("手机 " + PHONE + "。");
    }

    /**
     * 号码后面没有别的字符时也要扣住：还可能有下一位没送来。
     * 扣住只会让这一小段晚一点吐出去，放行则可能把整个号码漏出去——两边不对称，宁可扣。
     */
    @Test
    void 号码后面还没出现别的字符时继续扣住() {
        assertThat(PiiRedactor.safePrefix("手机 " + PHONE)).isEqualTo("手机 ");
    }

    /**
     * 拼起来的整体行为：一个号码被切成任意两块，逐块扣住 + 收尾吐出之后，
     * 结果必须与"一次性脱敏整段"完全相同。分块位置不该影响脱敏结果。
     */
    @Test
    void 任意切分后拼接的结果与整体脱敏一致() {
        String text = "联系他：" + PHONE + "，证件 " + ID_CARD + "。";

        for (int cut = 1; cut < text.length(); cut++) {
            StringBuilder streamed = new StringBuilder();
            StringBuilder pending = new StringBuilder();
            for (String piece : new String[] { text.substring(0, cut), text.substring(cut) }) {
                pending.append(piece);
                String safe = PiiRedactor.safePrefix(pending.toString());
                pending.delete(0, safe.length());
                streamed.append(PiiRedactor.mask(safe));
            }
            streamed.append(PiiRedactor.mask(pending.toString()));

            assertThat(streamed.toString()).as("在第 %d 个字处切断", cut).isEqualTo(PiiRedactor.mask(text));
        }
    }
}
