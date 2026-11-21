package io.github.claw4j.model.routing;

import io.github.claw4j.model.catalog.ModelRole;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 按角色取模型。{@code main} 永远有；{@code utility} 可以是"自己一条链"、"继承 main"或"关掉"。
 *
 * <p>四态照 OpenClaw 的 `src/agents/utility-model-setting.ts:12-35`：
 * <ul>
 *   <li>explicit：配了 {@code provider/model}，用它；
 *   <li>disabled：配了空串，<b>关掉</b>——不是继承。这一点最容易写错，静默继承会让"我明明关了"
 *       与"它还在花钱"同时成立；
 *   <li>auto：没配但打开了自动，取主供应商里最便宜的一行；
 *   <li>继承：完全没配，用 main 的那条链。
 * </ul>
 *
 * <p>本模块只给入口，不接消费方（票 12 的缺口）：谁切到 utility 由各自那张票决定。
 */
public final class ModelRoles {

    /** 角色 → 那条链。 */
    private final Map<ModelRole, ChatModel> byRole;

    /**
     * @param byRole 每个角色对应的模型；main 必须有
     */
    public ModelRoles(Map<ModelRole, ChatModel> byRole) {
        if (!byRole.containsKey(ModelRole.main)) {
            throw new IllegalArgumentException("ModelRoles 里必须有一条 main");
        }
        this.byRole = Map.copyOf(byRole);
    }

    /**
     * 取一个角色。
     *
     * @param role 角色
     * @return 对应模型；关掉的角色返回一个"一用就报错"的实现，不静默回落
     */
    public ChatModel forRole(ModelRole role) {
        ChatModel model = byRole.get(role);
        if (model == null) {
            throw new IllegalStateException("没有配置角色 " + role + "，也没有继承 main。"
                    + "要么在 claw4j.model.roles." + role + " 下配置，要么整段删掉（继承 main）。");
        }
        return model;
    }

    /**
     * 这个角色能不能用。
     *
     * @param role 角色
     * @return 关掉时为 false
     */
    public boolean enabled(ModelRole role) {
        return byRole.get(role) instanceof ChatModel && !(byRole.get(role) instanceof Disabled);
    }

    /**
     * 关掉的角色：调用就抛，让「谁还在用它」当场暴露，而不是悄悄回落 main。
     */
    public static final class Disabled implements ChatModel {

        private final ModelRole role;

        public Disabled(ModelRole role) {
            this.role = role;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new IllegalStateException("角色 " + role + " 已关闭（primary 配了空串），"
                    + "调用方不该在它关闭时还来取模型。");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.error(new IllegalStateException("角色 " + role + " 已关闭（primary 配了空串）"));
        }

        @Override
        public ChatOptions getOptions() {
            return ChatOptions.builder().build();
        }
    }
}
