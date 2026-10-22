package io.github.claw4j.agent;

import io.github.claw4j.agent.model.ModelProperties;
import io.github.claw4j.agent.model.RealChatModelCondition;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * 真实模型装配：有没有 key 决定装不装。
 *
 * <p>为什么自己构造而不吃 Spring AI 的自动配置：DeepSeek 的自动配置条件是
 * {@code spring.ai.model.chat=deepseek} 且 matchIfMissing=true，跟 key 无关。
 * 于是 classpath 里一旦有这个 starter，缺 key 时它照样激活并在构造时抛
 * "API key must be set"——应用根本起不来。
 *
 * <p>这里把 {@code spring.ai.model.chat} 设成 none 关掉厂商自动配置，
 * 改由本类按 key 有无决定：有 key 装配真实模型，没 key 什么都不装，
 * 由 {@link MockModelConfiguration} 兜底。于是"没有 key 也要能启动"和
 * "拿到 key 不用改代码"同时成立，且不需要 Maven profile 重新构建。
 */
@AutoConfiguration
@EnableConfigurationProperties(ModelProperties.class)
public class ModelConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ModelConfiguration.class);

    @Bean
    @Conditional(RealChatModelCondition.class)
    public ChatModel deepSeekChatModel(ModelProperties properties) {
        ModelProperties.DeepSeek config = properties.getDeepseek();
        String apiKey = config.getApiKey();

        if (apiKey.isEmpty()) {
            // 走到这里说明显式设了 mock=false 却没给 key。静默降级会掩盖配置错误，
            // 让启动失败并说清原因更好排查。
            throw new IllegalStateException(
                    "claw4j.model.mock=false 要求使用真实模型，但 claw4j.model.deepseek.api-key 为空。"
                            + "请设置环境变量 DEEPSEEK_API_KEY，或把 claw4j.model.mock 置为 true。");
        }

        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(apiKey)
                .build();

        // 用了哪个模型必须在日志里看得见，否则"到底有没有连上真实模型"只能靠猜。
        // 只打 baseUrl 与 model，不打 key。
        log.info("已装配 DeepSeek 真实模型：baseUrl={}, model={}", config.getBaseUrl(), config.getModel());

        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                // 必须显式给 model：默认 options 的 model 是 null，
                // 请求不带 model 时 DeepSeek 会返回空内容，而且不报错——很难排查。
                .options(DeepSeekChatOptions.builder()
                        .model(config.getModel())
                        .build())
                .build();
    }
}
