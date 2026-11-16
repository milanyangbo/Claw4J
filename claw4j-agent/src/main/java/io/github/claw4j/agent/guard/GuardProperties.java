package io.github.claw4j.agent.guard;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 入站拦截的配置。
 *
 * <p>词表在文件里（默认 {@code examples/guard/sensitive-words.txt}），一行一个词。
 * 不抄公开的中文敏感词库：那些是给内容审核用的，判据是"这句话能不能出现在平台上"，动辄几万条；
 * 这里的判据窄得多——"这条消息要不要直接拒答、连模型都不调"，误判的代价是一个人的问题被整轮拒掉。
 * 所以只覆盖两件确实会遇到的事：套取系统提示词、诱导越权与覆盖指令。
 *
 * <p>没配等于不装拦截：配置绑定对缺失的字符串只能传 null，这里把它归一到 null，
 * {@link SensitiveWordsConfigured} 再据此决定整个 Advisor 装不装。配了但文件读不到是另一回事——
 * 那种情况启动失败，见 {@link SensitiveWords#load(java.nio.file.Path)}。
 *
 * @param sensitiveWordsFile 词表文件路径，相对路径按启动时的工作目录解析；空表示不装拦截
 * @param failureResponse    命中时原样返回给用户的文本
 */
@ConfigurationProperties("claw4j.guard")
public record GuardProperties(String sensitiveWordsFile, String failureResponse) {

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
        sensitiveWordsFile = (sensitiveWordsFile == null || sensitiveWordsFile.isBlank())
                ? null : sensitiveWordsFile.trim();
        failureResponse = (failureResponse == null || failureResponse.isBlank())
                ? DEFAULT_FAILURE_RESPONSE : failureResponse.trim();
    }

    /**
     * 造一份全默认的配置：不配词表，默认答复。
     *
     * @return 不装拦截的配置
     */
    public static GuardProperties defaults() {
        return new GuardProperties(null, null);
    }
}
