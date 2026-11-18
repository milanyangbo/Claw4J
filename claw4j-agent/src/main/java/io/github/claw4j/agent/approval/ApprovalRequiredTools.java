package io.github.claw4j.agent.approval;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 哪些工具要人先批（票 05）。
 *
 * <p>这份名单是 ADR-0030 阶段 4 之后**唯一**的落点：业务权限登记表上那段"这个动作要人批"
 * 随整张表一起删掉，"要人看一眼"不再挂在业务权限上——它回答的是"这个动作有多重"
 * （谁在调、什么角色都不改变它），与"这个人能不能读这张表"是两件事。
 *
 * <p>默认空：今天没有任何生产工具声明要审批。exec 不走这里——它按 {@code security × ask} 在
 * 执行那一刻算，一条命令一判，按工具名一刀切表达不了"白名单命中就放行"。
 *
 * <p>只有一个 bean：这份名单是"整份"，不是"几个来源各报一份"。将来两个模块都要报时，改成一个
 * 聚合点把清单合并起来，而不是让容器里出现两个 {@code ApprovalRequiredTools}——那样注入方拿到的
 * 是"其中一个"，而它是哪一个取决于装配顺序。
 *
 * @param names 要人批的工具名
 */
public record ApprovalRequiredTools(Set<String> names) {

    public ApprovalRequiredTools {
        Set<String> cleaned = new LinkedHashSet<>();
        if (names != null) {
            for (String name : names) {
                if (name != null && !name.isBlank()) {
                    cleaned.add(name.strip());
                }
            }
        }
        names = Set.copyOf(cleaned);
    }

    /**
     * 这个工具要不要套审批闸。
     *
     * @param toolName 工具名，可以为 {@code null}
     * @return 要套时为 {@code true}
     */
    public boolean needsApproval(String toolName) {
        return toolName != null && names.contains(toolName.strip());
    }

    /**
     * 一个都不要：默认那一档。
     *
     * @return 空名单
     */
    public static ApprovalRequiredTools none() {
        return new ApprovalRequiredTools(Set.of());
    }
}
