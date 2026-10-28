package io.github.claw4j.domain.principal;

import java.util.Objects;
import java.util.Set;

/**
 * 调用一个工具需要什么权限：一个动作名 + 一张表清单（ADR-0007）。
 *
 * <p>粒度是**表级与动作级**，行级与列级不做。这两级够讲清设计了：
 * "能不能查 {@code sales_order}"是表级，"能不能发邮件"是动作级。
 *
 * <p>动作名是粗粒度动词而不是工具名：角色的授权表里写的是 {@code read} / {@code send_email}
 * 这种人读得懂的词，把工具名写进角色配置会让"谁能干什么"变成一份工具清单，
 * 加一个工具就要改一次角色配置。将来 MCP 工具按工具名授权是另一层的事（ADR-0003 的第二层）。
 *
 * @param action 动作名，与 {@link Principal#canPerform(String)} 的取值同一个口径
 * @param tables 这个工具会碰到的表；全部都要在 Principal 的可访问范围内才放行
 */
public record ToolPermission(String action, Set<String> tables) {

    /** 读动作。只读角色拿到的就是它，所以"只读"能查数但不能发邮件。 */
    public static final String READ = "read";

    public ToolPermission {
        Objects.requireNonNull(action, "action");
        tables = Set.copyOf(tables);
    }

    public static ToolPermission read(String... tables) {
        return new ToolPermission(READ, Set.of(tables));
    }

    public static ToolPermission of(String action, String... tables) {
        return new ToolPermission(action, Set.of(tables));
    }

    /**
     * 这个权限是否授予给了该身份。
     *
     * <p>两个条件都要满足：动作被允许，且**每一张**表都能读。表那一条用"全部"而不是"任一"——
     * 工具会触碰的表只要有一张越权，这次调用就越权。
     */
    public boolean grantedTo(Principal principal) {
        Objects.requireNonNull(principal, "principal");
        if (!principal.canPerform(action)) {
            return false;
        }
        return tables.stream().allMatch(principal::canReadTable);
    }
}
