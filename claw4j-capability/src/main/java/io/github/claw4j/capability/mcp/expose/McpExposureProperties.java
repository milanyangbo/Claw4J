package io.github.claw4j.capability.mcp.expose;

import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/**
 * 把自己的工具交出去：{@code claw4j.mcp.server} 这一段（P11）。
 *
 * <p>默认关，而且默认一个工具都不给。两个默认不是一回事：{@code enabled} 管"这台机器要不要
 * 在 stdout 上开一个 MCP server"，{@code expose} 管"交出去哪几个"。后者只能是空——
 * 按"能读的都自动交出去"推断的话，一个装上插件、接了几个 MCP server 的部署会把自己都
 * 数不清的工具一次交出去，而这件事在 review 里看不出来（配置文件一行都没变）。
 *
 * <p>交出去的每一个名字都是一次被人做过的决定，代价是第三方插件默认不出现，要暴露得写名字。
 * 这与 P09 的档位是同一个取向：默认值不替人做决定。
 *
 * <p>{@code tables} 是外部客户端能读的表，与 IM 里那张角色授权表无关：外部客户端没有 IM 身份
 * （没有 open_id、没有成员 UserID），拿它去查角色表只能靠猜。所以它自成一个身份，
 * 默认 {@code ["*"]}，与 {@link Principal#readOnly} 同一个口径。收窄它意味着"这个部署只让
 * 外部客户端读这几张表"，而哪个工具需要哪几张表由工具的登记表说了算：一张对不上，
 * 调用就被拒（落在调用那一刻，与 IM 侧鉴权 Advisor 的位置相同，不是启动时判死）。
 *
 * @param enabled 总开关，缺省 false
 * @param name MCP server 的名字，缺省 {@code claw4j}
 * @param expose 交出去的工具名；空表示什么都不交，而"开着却什么都不交"是启动失败
 * @param tables 外部客户端能读的表，缺省 {@code ["*"]}
 */
@ConfigurationProperties("claw4j.mcp.server")
public record McpExposureProperties(boolean enabled, String name, List<String> expose, Set<String> tables) {

    public McpExposureProperties {
        name = name == null || name.isBlank() ? "claw4j" : name;
        expose = expose == null ? List.of() : List.copyOf(expose);
        tables = tables == null || tables.isEmpty() ? Set.of(Principal.WILDCARD) : Set.copyOf(tables);
    }

    /**
     * 外部客户端是谁。
     *
     * <p>角色写 {@code mcp-client} 而不是复用 {@code read-only}：两者的动作集合此刻相同，
     * 但它们是两种身份，将来"给外部客户端多一个动作"与"给只读用户多一个动作"不该是同一句话。
     * 动作集合只有 {@link ToolPermission#READ}——写动作没有入口，不是靠调用时拒绝。
     *
     * @param clientName MCP 握手时客户端自报的名字，可能为空（协议允许不报）
     */
    public Principal principal(String clientName) {
        String userId = clientName == null || clientName.isBlank() ? "mcp" : "mcp:" + clientName;
        return new Principal(userId, "mcp-client", tables, Set.of(ToolPermission.READ));
    }
}
