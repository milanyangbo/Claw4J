package io.github.claw4j.agent.model;

/**
 * DeepSeek 这一家的装配条件，判定逻辑在 {@link RealChatModelCondition}。
 */
public class DeepSeekModelCondition extends RealChatModelCondition {

    /** 这一家的 key 属性名。 */
    public static final String API_KEY_PROPERTY = "claw4j.model.deepseek.api-key";

    @Override
    protected String apiKeyProperty() {
        return API_KEY_PROPERTY;
    }

    @Override
    protected String label() {
        return "DeepSeek";
    }
}
