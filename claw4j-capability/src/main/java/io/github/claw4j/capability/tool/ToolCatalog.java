package io.github.claw4j.capability.tool;

import io.github.claw4j.domain.principal.ToolProfile;
import org.springframework.ai.tool.ToolCallback;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 这个部署的工具清单：**模型能用哪些工具**，以及它是按哪一档筛出来的。
 *
 * <p>它存在的原因只有一个：清单原来只是 {@code AgentAutoConfiguration#chatClient} 里的一个局部变量，
 * 拼完交给 {@code ChatClient} 就没人见过它。在有第二个出口（MCP server，P11）之前这没有问题——
 * 一份清单一个消费者。有了第二个出口之后，两条路各自收一遍工具，P09 的档位、{@code MeteredToolCallback}
 * 那层包装、权限登记表就会各自漂一次，而且**漂了不会报错**：外部客户端看到的是一个看起来正常、
 * 其实绕过了三道门的清单。
 *
 * <p>所以清单被提成 bean，两个出口吃同一份。判据因此可以写成一句可断言的话：
 * 外部客户端看到的工具，是模型能用的工具的**子集**——子集而不是相等，是因为有些工具
 * 对"零上下文的客户端"没有意义（比如"检索工具"对客户端自己的工具列表毫无用处）。
 *
 * <p><b>{@code profile} 跟着清单一起走</b>：它回答了"这份清单是哪一档筛出来的"。
 * 让消费方自己再算一遍，等于把"档位从哪来"（人设文件里的 tier 还是 {@code claw4j.tools.profile}）
 * 这件事复制到第二处，而这两处的分支条件将来一定会分开。
 *
 * @param agent   这份清单属于哪个 agent（人设名），只用于日志与 MCP 的 serverInfo
 * @param profile 筛出这份清单用的档位与 allow / deny
 * @param tools   筛完之后的工具，已包过计量与预算
 */
public record ToolCatalog(String agent, ToolProfile profile, List<ToolCallback> tools) {

    public ToolCatalog {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(profile, "profile");
        tools = List.copyOf(tools);
    }

    /**
     * 工具名集合。用 {@link LinkedHashSet} 保留顺序：清单的顺序就是启动日志里那一行的顺序，
     * 排查时能直接与 yml 对上。
     */
    public Set<String> names() {
        Set<String> names = new LinkedHashSet<>();
        for (ToolCallback tool : tools) {
            names.add(tool.getToolDefinition().name());
        }
        return names;
    }

    /**
     * 按名字取工具。重名不在这里处理：两个来源导出同名工具是配置错误，
     * 该在清单断言上红（P09 的收集点已经这么做了），而不是在这里悄悄取第一个。
     */
    public Optional<ToolCallback> byName(String name) {
        return tools.stream()
                .filter(tool -> tool.getToolDefinition().name().equals(name))
                .findFirst();
    }
}
