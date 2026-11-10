package io.github.claw4j.agent.guard;

import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;

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
 * <p>没有 enabled 开关：装配与否由词表是否为空决定（{@link SensitiveWordsConfigured}）。再加
 * 一个布尔开关，就会出现「开关打开但词表是空的」这种既没生效又看不出来的组合。
 */
@AutoConfiguration
@ConditionalOnClass(SafeGuardAdvisor.class)
@EnableConfigurationProperties(GuardProperties.class)
@Conditional(SensitiveWordsConfigured.class)
public class GuardAutoConfiguration {

    /**
     * 全链最小，也就是 {@code before} 最先跑。
     *
     * <p>必须早于会话记忆（{@code SessionMemoryConfiguration.ADVISOR_ORDER}）。记忆在它的
     * {@code before} 里把历史消息拼进 prompt，而 {@link SafeGuardAdvisor} 检查的是
     * {@code prompt().getContents()} 的全部内容。排在记忆里层的话，只要历史里有一轮出现过某个
     * 词，之后每一轮都会被同一个词拦下，会话就此锁死，而用户看到的只是「从某一句话开始，
     * 怎么问都不理我了」。排在最外层，它看到的就是这一条用户消息本身。
     *
     * <p>代价是历史里已经存在的词不再被拦。这是有意的：历史只可能装着当时被放行的内容，而当时
     * 放行的理由就是那一条不含词表里的词。要管住历史，该在写入时管，不是在读出来时管。
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

    /**
     * 按配置装一个入站拦截器。
     *
     * @param properties 词表与命中时的答复
     * @return 挂在全链最前的拦截器
     */
    @Bean
    @ConditionalOnMissingBean(SafeGuardAdvisor.class)
    public SafeGuardAdvisor safeGuardAdvisor(GuardProperties properties) {
        return SafeGuardAdvisor.builder()
                .sensitiveWords(properties.sensitiveWords())
                .failureResponse(properties.failureResponse())
                .order(ORDER)
                .build();
    }
}
