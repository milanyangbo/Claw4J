package io.github.claw4j.domain.port;

import io.github.claw4j.domain.principal.Principal;

import java.util.Optional;

/**
 * 按"这是谁、他是什么角色"取<b>此刻</b>的权限（P05）。
 *
 * <p><b>它存在的唯一理由是不许存权限快照</b>：定时任务要在一个没人发消息的时刻跑，
 * 于是"用什么身份跑"必须提前落库。落整份 {@link Principal}（含可读表与可执行动作）最省事，
 * 但那样一来，权限被收回之后那条任务仍然按旧权限跑——收权对定时任务等于没发生。
 * 所以库里只留 userId 与角色名，每次要跑之前来这里换一份<b>当前</b>的权限。
 *
 * <p>回答不上来就是 {@link Optional#empty()}：角色名已经不在授权表里了，或者这个人
 * 被从角色里摘掉了。这两个都是"现在开始不能跑"，调用方要停掉而不是猜一个身份继续。
 *
 * <p><b>实现放在通道那一侧</b>：角色表现在是部署配置（{@code claw4j.channel.http.roles}），
 * 而配置归谁读是部署的事。放在 domain 或 agent 里，等于让内核去认一份它不该认识的文件。
 */
public interface RoleDirectory {

    /**
     * @param userId 通道侧的标识（飞书 open_id、HTTP 的用户名）
     * @param role   角色名
     * @return 这个角色现在的权限；角色没了、或者认不出这个人，都是空
     */
    Optional<Principal> current(String userId, String role);
}
