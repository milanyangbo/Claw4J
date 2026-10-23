package io.github.claw4j.agent.model;

/**
 * 通义这一家的装配条件，判定逻辑在 {@link RealChatModelCondition}。
 */
public class QwenModelCondition extends RealChatModelCondition {

    public static final String API_KEY_PROPERTY = "claw4j.model.qwen.api-key";

    @Override
    protected String apiKeyProperty() {
        return API_KEY_PROPERTY;
    }

    @Override
    protected String label() {
        return "通义（Qwen）";
    }
}
