package io.github.claw4j.domain.principal;

import java.util.Objects;
import java.util.Set;

/**
 * 调用一个工具需要什么权限：一个动作名 + 一张表清单（ADR-0007）+ 要不要人先批（P01）。
 *
 * <p>粒度是表级与动作级，行级与列级不做。这两级够讲清设计了："能不能查
 * {@code sales_order}"是表级，"能不能发邮件"是动作级。
 *
 * <p>动作名是粗粒度动词而不是工具名：角色的授权表里写的是 {@code read} / {@code send_email}
 * 这种人读得懂的词，把工具名写进角色配置会让"谁能干什么"变成一份工具清单，加一个工具就要改
 * 一次角色配置。MCP 工具按工具名授权是另一层的事（ADR-0003 的第二层）。
 *
 * <p>第三个分量与鉴权并列，不是它的一种结果：{@link #grantedTo} 只回答"这个人能不能干"，
 * 一个字都不看 {@link #approval()}——标了 {@link Approval#REQUIRED} 的权限，对只读身份依然
 * 是"不授予"，对人批过的身份也依然是"不授予"。审批解决的是"有权限的人动作太重"，不是"没有
 * 权限的人想绕过鉴权"。
 *
 * @param action   动作名，与 {@link Principal#canPerform(String)} 的取值同一个口径
 * @param tables   这个工具会碰到的表；全部都要在 Principal 的可访问范围内才放行
 * @param approval 要不要人先批；默认 {@link Approval#NONE}
 */
public record ToolPermission(String action, Set<String> tables, Approval approval) {

    /** 读动作。只读角色拿到的就是它，所以"只读"能查数但不能发邮件。 */
    public static final String READ = "read";

    public ToolPermission {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(approval, "approval");
        tables = Set.copyOf(tables);
    }

    /**
     * 读权限。
     *
     * @param tables 这次调用会碰到的表
     * @return 动作为 {@link #READ}、不需要审批的权限
     */
    public static ToolPermission read(String... tables) {
        return new ToolPermission(READ, Set.of(tables), Approval.NONE);
    }

    /**
     * 不带审批的那一档。全仓的工具走的都是它：加了第三个分量不等于收紧了谁。
     *
     * @param action 动作名
     * @param tables 这次调用会碰到的表
     * @return 不需要审批的权限
     */
    public static ToolPermission of(String action, String... tables) {
        return new ToolPermission(action, Set.of(tables), Approval.NONE);
    }

    /**
     * 要人先批的那一档。
     *
     * <p>单开一个工厂而不是给 {@link #of} 加一个可选分量：调用点写出来就该看得出"这个动作有人
     * 批才动"，而不是让读者去数第三个参数是不是 {@code NONE}。
     *
     * @param action 动作名
     * @param tables 这次调用会碰到的表
     * @return 需要审批的权限
     */
    public static ToolPermission requiringApproval(String action, String... tables) {
        return new ToolPermission(action, Set.of(tables), Approval.REQUIRED);
    }

    /**
     * 这个权限是否授予给了该身份。
     *
     * <p>两个条件都要满足：动作被允许，且每一张表都能读。表那一条用"全部"而不是"任一"——
     * 工具会触碰的表只要有一张越权，这次调用就越权。
     *
     * <p>这里不看审批：鉴权与审批是两道门，先过的这道只回答"能不能"。把两条混在一个返回值里，
     * 拒绝话术就再也没法说清"是没权限还是等你批"。
     *
     * @param principal 发起调用的人
     * @return 动作与表都满足时为 {@code true}，与 {@link #approval()} 无关
     * @throws NullPointerException {@code principal} 为 {@code null}
     */
    public boolean grantedTo(Principal principal) {
        Objects.requireNonNull(principal, "principal");
        if (!principal.canPerform(action)) {
            return false;
        }
        return tables.stream().allMatch(principal::canReadTable);
    }

    /**
     * 要不要人先批。鉴权通过之后由鉴权 Advisor 再问一句（P01 的落点）。
     *
     * @return 标了 {@link Approval#REQUIRED} 时为 {@code true}
     */
    public boolean needsApproval() {
        return approval == Approval.REQUIRED;
    }
}
