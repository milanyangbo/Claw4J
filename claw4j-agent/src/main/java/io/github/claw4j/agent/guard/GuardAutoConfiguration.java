package io.github.claw4j.agent.guard;

import io.github.claw4j.agent.advisor.AdvisorOrder;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import java.nio.file.Path;

/**
 * 入站拦截（{@link SafeGuardAdvisor}）的装配。
 *
 * <p>它拦的是「要不要把这条消息送去模型」，不是「回答里能不能出现某个词」。命中时框架直接
 * 返回一句固定答复、不调用链上后续的 Advisor，于是一次注入尝试连 token 都不花，也不会进会话
 * 历史：记忆在它里层，看不到这一轮。
 *
 * <p>与出站脱敏是两件事，不能互相替代。这里做关键词子串匹配，命中即整轮拒答；
 * {@link io.github.claw4j.agent.advisor.PiiRedactionAdvisor} 按格式识别手机号、证件号并打码后
 * 照常回答。业务上「这单的收货手机号是多少」必须答得出来，把手机号写进这张词表等于把这个能力
 * 删掉；反过来，词表里不写手机号格式也挡不住号码外泄——它不懂格式，连全角手机号都认不出
 * （{@link SafeGuardAdvisor} 的类注释明说不处理 homoglyph、全角与零宽字符，
 * spring-ai-client-chat 2.0.2 {@code SafeGuardAdvisor.java:42}）。两者位置也不同：一个在
 * 入站，一个在出站。
 *
 * <p>没有 enabled 开关：装配与否由词表文件里有没有词决定（{@link SensitiveWordsConfigured}）。再加
 * 一个布尔开关，就会出现「开关打开但词表是空的」这种既没生效又看不出来的组合。
 */
@AutoConfiguration
@ConditionalOnClass(SafeGuardAdvisor.class)
@EnableConfigurationProperties(GuardProperties.class)
@Conditional(SensitiveWordsConfigured.class)
public class GuardAutoConfiguration {

    /**
     * 按配置装一个入站拦截器，词从文件读。
     *
     * <p>{@code properties.sensitiveWordsFile()} 在这里不会是 null：这条装配本身就挂在
     * {@link SensitiveWordsConfigured} 上，路径没配时它不会跑。
     *
     * <p>位置见 {@link AdvisorOrder#GUARD}：全链最外，看到的是这一条用户消息本身，而不是拼上历史
     * 之后的整段 prompt。
     *
     * @param properties 词表文件与命中时的答复
     * @return 挂在全链最前的拦截器
     */
    @Bean
    @ConditionalOnMissingBean(SafeGuardAdvisor.class)
    public SafeGuardAdvisor safeGuardAdvisor(GuardProperties properties) {
        return SafeGuardAdvisor.builder()
                .sensitiveWords(SensitiveWords.load(Path.of(properties.sensitiveWordsFile())))
                .failureResponse(properties.failureResponse())
                .order(AdvisorOrder.GUARD)
                .build();
    }
}
