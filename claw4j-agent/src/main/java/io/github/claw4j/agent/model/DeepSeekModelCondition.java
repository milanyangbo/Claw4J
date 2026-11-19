package io.github.claw4j.agent.model;

/**
 * DeepSeek 这一家的装配条件，判定逻辑在 {@link RealChatModelCondition}。
 */
public class DeepSeekModelCondition extends RealChatModelCondition {

    @Override
    protected String providerName() {
        return ModelProperties.DEEPSEEK;
    }

    @Override
    protected String label() {
        return "DeepSeek";
    }
}
