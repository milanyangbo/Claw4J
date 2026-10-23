package io.github.claw4j.agent.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.retry.RetryUtils;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真模型测试的公共支撑（ADR-0011）：连真实 DeepSeek，不再有假模型。
 *
 * <p><b>没有 key 就 fail，不跳过。</b>被跳过与被排除在外的测试会被
 * {@code scripts/assert-no-skipped-tests.sh} 放过——那正是 M1 那个 P0 的形状
 * （26 条数据库测试从未执行却被当成绿）。所以缺 key 时这里抛的是 {@link AssertionError}，
 * 表现为**测试失败并写明去哪配**，而不是安静地"没跑"。
 *
 * <p><b>温度 0</b>：同一个问题两次问出两个不同的数是故障，不是个性；
 * 而且非零温度等于给每条断言都掺一份随机。这是压 flaky 最便宜的一招，先于重试。
 *
 * <p><b>有限重试</b>：真实模型不保证每次都调工具（ADR-0006 记过一次
 * "通义不调工具、直接反问口径"）。{@link #eventually} 只在断言失败时重试，
 * 并且**每一次都打日志**——否则"它其实一直没调工具"会被重试掩盖住，
 * 看起来绿，实际是靠重试撑着。
 *
 * <p>这里刻意不复用 {@code ModelConfiguration}：那一份测的是"按 key 装配哪家"
 * （{@code ModelSelectionTest} 的职责）。端到端测试只需要一个真模型实例，
 * 直接构造更省一层容器，出错时也少一个排查环节。
 */
public final class RealModelSupport {

    public static final String API_KEY_ENV = "DEEPSEEK_API_KEY";

    public static final String BASE_URL_ENV = "DEEPSEEK_BASE_URL";

    public static final String MODEL_ENV = "DEEPSEEK_MODEL";

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    private static final String DEFAULT_MODEL = "deepseek-chat";

    private static final Logger log = LoggerFactory.getLogger(RealModelSupport.class);

    private RealModelSupport() {
    }

    /**
     * 取 key，没有就让测试失败。
     *
     * <p>用 {@link AssertionError} 而不是 {@code Assumptions.assumeTrue(...)}：
     * 后者产生的是 skipped，而跳过不被当作失败——这类测试一旦被跳过，
     * "模型侧到底验过没有"就没人知道了。
     */
    public static String apiKeyOrFail() {
        String key = System.getenv(API_KEY_ENV);
        if (key == null || key.isBlank()) {
            throw new AssertionError("需要环境变量 " + API_KEY_ENV + " 才能跑真模型测试（ADR-0011）："
                    + "测试连的是真实 DeepSeek，没有 key 就是没验过，不能算绿。"
                    + "本地把 key 写进 .env 后 export，CI 上配 secrets." + API_KEY_ENV + "。");
        }
        return key.trim();
    }

    /** 真实 DeepSeek 模型：温度 0、短重试（与生产同一套超时取向）。 */
    public static ChatModel chatModel() {
        String baseUrl = env(BASE_URL_ENV, DEFAULT_BASE_URL);
        String model = env(MODEL_ENV, DEFAULT_MODEL);

        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKeyOrFail())
                .build();

        log.info("真模型测试装配 DeepSeek：baseUrl={}, model={}, temperature=0.0", baseUrl, model);

        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .options(DeepSeekChatOptions.builder()
                        .model(model)
                        .temperature(0.0)
                        .build())
                // 与生产一致：模型内部只留短重试，真出问题让上层去处理，不在这里指数退避
                .retryTemplate(RetryUtils.SHORT_RETRY_TEMPLATE)
                .build();
    }

    /**
     * 最多跑 {@code attempts} 次，只要有一次通过就算过；全不过就抛出最后一次的失败。
     *
     * <p>只重试断言失败（{@link AssertionError}）。网络异常与配置错误<b>不重试</b>：
     * 那是真故障，重试只会把"供应商挂了"显示成"多花了几次调用之后还是挂了"。
     */
    public static <T> T eventually(int attempts, String what, Supplier<T> action) {
        AssertionError last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return action.get();
            } catch (AssertionError failure) {
                last = failure;
                log.warn("真模型第 {}/{} 次没达到预期（{}）：{}",
                        attempt, attempts, what, failure.getMessage());
            }
        }
        throw last;
    }

    /** 同步读一次模型超时给宽一点：真模型第一次调用要建连接，容器里还要起库。 */
    public static Duration generousTimeout() {
        return Duration.ofSeconds(120);
    }

    /**
     * 断言"回答里出现了这个数"，允许各种写法。
     *
     * <p>真模型给的数是 <b>128,400.00</b>——带千分位、带两位小数，前后还有一堆口径说明。
     * 只匹配 {@code 128400} 会在千分位上假红，而这里要抓的是"查不到数""编了一个数"，
     * 不是措辞。所以把同一个数的几种写法都算命中：
     * {@code 128,400.00 / 128400.00 / 128,400 / 128400}。
     */
    public static void assertContainsAmount(String answer, String digits) {
        List<String> forms = amountForms(digits);
        assertThat(forms.stream().anyMatch(answer::contains))
                .as("回答里应当出现 %s 的某种写法 %s，实际拿到的是：%s", digits, forms, answer)
                .isTrue();
    }

    /** 一个数的几种写法：加千分位、加小数、都不加。 */
    public static List<String> amountForms(String digits) {
        long value = Long.parseLong(digits);
        String grouped = String.format("%,d", value);
        return List.of(grouped + ".00", digits + ".00", grouped, digits);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
