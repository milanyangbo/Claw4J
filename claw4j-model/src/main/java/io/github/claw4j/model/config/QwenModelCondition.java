package io.github.claw4j.model.config;

/**
 * 通义这一家的装配条件，判定逻辑在 {@link RealChatModelCondition}。
 *
 * <p>除了"装不装 qwen 这个聊天模型"，它还被向量模型（{@code EmbeddingAutoConfiguration}）与
 * 语义缓存（{@code CacheAutoConfiguration}）复用：这两样只有通义能给，判据就是
 * "providers 里 name=qwen 那一条的句柄有没有真值"。
 */
public class QwenModelCondition extends RealChatModelCondition {

    @Override
    protected String providerName() {
        return ModelProperties.QWEN;
    }

    @Override
    protected String label() {
        return "通义（Qwen）";
    }
}
