package io.github.claw4j.capability.builtin.agenttools;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 联网工具的配置。只有搜索需要配置项：{@code WebFetch} 不需要凭据。
 *
 * <p>没有 key 就不装配 {@code WebSearch}，而不是装一个"调用时才说缺 key"的工具：后者会让模型
 * 反复试一个注定失败的动作，而"这个部署有没有搜索"本来是看配置就能回答的问题。
 *
 * @param searchApiKey Brave Search 的订阅 token；空表示不装搜索
 */
@ConfigurationProperties("claw4j.tools.web")
public record WebToolsProperties(String searchApiKey) {

    public WebToolsProperties {
        searchApiKey = searchApiKey == null || searchApiKey.isBlank() ? null : searchApiKey.strip();
    }

    /**
     * 这个部署有没有搜索。
     *
     * @return 配了 key 时为 {@code true}
     */
    public boolean hasSearchApiKey() {
        return searchApiKey != null;
    }
}
