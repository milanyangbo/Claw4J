package io.github.claw4j.model.catalog;

import java.util.Locale;

/**
 * 按用途取模型的两个角色。
 *
 * <p>只有两个，照 ADR-0035 第七节：{@code main} 是回答用户的那条链，{@code utility} 是
 * "用户看不见的短任务"（会话压缩摘要这类）。行业微调模型不是第三个角色——它是 main 候选里的
 * 一行，换掉哪一行就换了用哪个模型，不需要新概念。
 *
 * <p>{@code decision}（判型决策）不接：本仓没有那个消费方，先不加空角色。
 */
public enum ModelRole {

    /** 回答用户的主链。 */
    main,

    /** 用户看不见的内部短任务。 */
    utility;

    /**
     * 从配置键解析。
     *
     * @param text 角色名
     * @return 角色
     * @throws IllegalArgumentException 不认识的名字
     */
    public static ModelRole parse(String text) {
        if (text != null) {
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            for (ModelRole role : values()) {
                if (role.name().equals(normalized)) {
                    return role;
                }
            }
        }
        throw new IllegalArgumentException("不认识的角色 " + text + "，只有 " + java.util.Arrays.toString(values()));
    }
}
