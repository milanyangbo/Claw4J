package io.github.claw4j.agent.guard;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 入站拦截的配置。
 *
 * <p>词表自己写，不抄现成的：Spring AI 的 {@code SafeGuardAdvisor} 只做大小写不敏感的子串
 * 匹配，也明说它「can be used as a starting point」（spring-ai-client-chat 2.0.2，
 * {@code SafeGuardAdvisor.java:40-42}），没有附带词表。公开的中文敏感词库（sensitive-word
 * 那一类）是给内容审核用的，动辄几万条，判据是这句话能不能出现在平台上；这里是这条消息要不要
 * 直接拒答、连模型都不调，判据窄得多。把审核词库塞进来，用户说一句「这个客户真难缠」就可能
 * 整轮被拒。所以这里的表只覆盖两件确实会遇到的事：套取系统提示词、诱导越权与覆盖指令。
 *
 * <p>没配等于空表：配置绑定对缺失的键只能传 null，而配了一个空列表走的是另一条路。两者在这件
 * 事上是同一件事——没有要拦的词，所以构造器把 null 归一到空列表，
 * {@link SensitiveWordsConfigured} 再依据归一后为空决定整个 Advisor 装不装。同类坑见票 06：
 * {@code int} 字段被绑成 0 而不是没配，而 0 在那里恰好是个有效值。
 *
 * @param sensitiveWords 命中任一即拒答。大小写不敏感，子串匹配
 * @param failureResponse 命中时原样返回给用户的文本
 */
@ConfigurationProperties("claw4j.guard")
public record GuardProperties(List<String> sensitiveWords, String failureResponse) {

    /**
     * 命中时的答复。
     *
     * <p>文案里不写命中了什么词：那等于把词表一字一句告诉想绕过的人，他换一个同义词就过去了。
     * 也不写「我怀疑你在攻击」——大多数命中的人只是问法碰巧撞上，一句话把他定性成攻击者，
     * 比拒答本身更糟。只说换个说法。
     */
    public static final String DEFAULT_FAILURE_RESPONSE =
            "这条消息我没法处理，换个说法再试一次。";

    public GuardProperties {
        sensitiveWords = sensitiveWords == null ? List.of()
                : sensitiveWords.stream()
                        .filter(word -> word != null && !word.isBlank())
                        .map(String::trim)
                        .toList();
        failureResponse = (failureResponse == null || failureResponse.isBlank())
                ? DEFAULT_FAILURE_RESPONSE : failureResponse.trim();
    }

    /**
     * 造一份全默认的配置：空词表，默认答复。
     *
     * @return 空词表的配置，装配时等于不装拦截
     */
    public static GuardProperties defaults() {
        return new GuardProperties(null, null);
    }
}
