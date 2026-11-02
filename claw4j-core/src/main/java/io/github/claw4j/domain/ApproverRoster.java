package io.github.claw4j.domain;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 谁能批（P03）：一份独立的人名单，与业务角色无关。
 *
 * <p><b>为什么不复用角色</b>：角色回答的是"他能读哪些表、能跑哪些动作"，审批回答的是
 * "他愿意为别人的操作负责"。两件事在真实的组织里经常不是同一批人——把审批权挂到
 * {@code admin} 角色上，等于"能读就能批"，那么任何一次提权都会静默地扩大审批面；
 * 反过来，一个只负责点头的人不必拥有读全表的权限。分开之后，改角色配置不会改变谁能批，
 * 反过来也一样，这两件事的审计也才分得开。
 *
 * <p><b>名单为空就是没人能批</b>，不是"人人能批"。这是这个项目一贯的方向（没有审批设施时
 * {@code ApprovalGate#denyAll()} 拦住一切）：忘配置的后果必须是"卡住"，而不是"谁都能点头"。
 * 代价是忘配置时所有待批请求都会过期——所以装配时会在日志里说一声（见 {@code ApprovalService}）。
 *
 * <p><b>提问人不能批自己的请求</b>：自行批准等于没有审批。判据在这里而不是在入口，
 * 因为入口有两个（HTTP 与飞书卡片，P03），写在入口上的检查早晚有一处漏掉——
 * 而漏掉的那一处表现是"同一个按钮，换个通道就能自己批自己"。
 *
 * <p>按 {@code Principal#userId()} 比：HTTP 那一侧是请求头里的用户名，飞书那一侧是
 * {@code open_id}（见 {@code FeishuPrincipalResolver}），两个 id 空间各自独立，
 * 所以名单里写哪种取决于那个人从哪个通道来。
 *
 * @param approvers 能批的人。空集合表示没人能批
 */
public record ApproverRoster(Set<String> approvers) {

    public ApproverRoster {
        approvers = approvers == null ? Set.of() : copy(approvers);
    }

    /**
     * 这个人能不能批这一条。
     *
     * @param approver 点按钮的人
     * @param owner    提出这条请求的人
     * @return 空表示可以批；否则是拒绝的理由档位——
     *         {@link ApprovalVerdict#NOT_APPROVER}（不在名单里）或
     *         {@link ApprovalVerdict#SELF_APPROVAL}（自己的请求）
     */
    public Optional<ApprovalVerdict> refuse(String approver, String owner) {
        // 名单先于自批：不在名单里的人，说"你不是审批人"比说"这是你自己的请求"更根本——
        // 后者会让他以为换一件事就能批。
        if (!approvers.contains(approver == null ? "" : approver.strip())) {
            return Optional.of(ApprovalVerdict.NOT_APPROVER);
        }
        if (approver.strip().equals(owner == null ? "" : owner.strip())) {
            return Optional.of(ApprovalVerdict.SELF_APPROVAL);
        }
        return Optional.empty();
    }

    /** 一个人都没有。装配时要据此在启动日志里说一句，否则表现是所有请求默默过期。 */
    public boolean nobody() {
        return approvers.isEmpty();
    }

    private static Set<String> copy(Set<String> approvers) {
        Set<String> cleaned = new LinkedHashSet<>();
        for (String each : approvers) {
            if (each != null && !each.isBlank()) {
                cleaned.add(each.strip());
            }
        }
        return Set.copyOf(cleaned);
    }
}
