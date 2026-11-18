package io.github.claw4j.agent.schedule;

import io.github.claw4j.agent.approval.ApprovalRequiredTools;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 判断一条定时任务能不能建、到点还要不要跑（P05）。
 *
 * <p>建的时候问一次，每次到点之前再问一次。两个调用点问的是同一个问题：分开写的话两边迟早
 * 不一致，最常见的是建的时候查、到点不查，于是配置改了之后任务照跑，而那时已经没人看着。
 *
 * <p>现在只剩一条判据：**要人批的工具不能进定时任务**。到点那一刻没有人能点那个按钮，结果是
 * 每天挂一条没人看见的待批请求，而不是每天办成一件事。这类工具不属于"一次授权、多次执行"。
 *
 * <p>原来还有两条（"任务主人此刻是什么角色、还有没有权限""任务声明的工具他还能不能用"），
 * 随角色目录与权限登记表在 ADR-0030 里一起下去了。那时候的判据是业务数据授权，
 * 而这一层现在只做"大模型能看见什么"与"执行前要不要人点头"；到点用 {@code owner_user_id}
 * 起跑就是全部的身份事实。
 */
public class ScheduleAuthorization {

    /** 哪些工具要人先批。跟着 {@code claw4j.approval.required-tools} 走，默认为空名单。 */
    private final ApprovalRequiredTools approvals;

    /**
     * @param approvals 要人批的工具名单，必填
     */
    public ScheduleAuthorization(ApprovalRequiredTools approvals) {
        this.approvals = Objects.requireNonNull(approvals, "approvals");
    }

    /**
     * 这条任务现在能不能跑。
     *
     * @param requiredTools 任务自己声明的工具名，可以为空（纯模型回答、不碰工具）
     * @return 空表示能跑；否则是一句拒绝理由，建的时候交给模型，到点的时候写进日志与停用原因
     */
    public Optional<String> refusalFor(List<String> requiredTools) {
        for (String name : requiredTools == null ? List.<String>of() : requiredTools) {
            if (approvals.needsApproval(name)) {
                return Optional.of("工具 " + name + " 每执行一次都要有人批准，"
                        + "而到点那一刻没有人能点那个按钮");
            }
        }
        return Optional.empty();
    }
}
