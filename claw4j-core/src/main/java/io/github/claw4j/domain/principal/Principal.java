package io.github.claw4j.domain.principal;

import java.util.Objects;

/**
 * 发起请求的人：只有"这是谁"这一件事（ADR-0030）。
 *
 * <p>它曾经还带角色、可读的表、可执行的动作，并据此决定一次工具调用能不能发生（ADR-0007）。
 * 那一层在 ADR-0030 里整块下线：权限现在只有大模型这一层——**看得见什么**由工具清单决定
 * （{@code ToolProfile} 与策略管线），**执行前要不要人点头**由审批决定（{@code ExecPolicy}）。
 * 业务数据范围、知识库 ACL、按用户分配的细粒度授权是第二步的事，那时会以另一套形态重建，
 * 而不是把表与动作塞回这里。
 *
 * <p>留住 {@code userId} 的理由是三件与权限无关的事：会话键按人分段、审计要记"谁让什么工具
 * 做了什么"、审批要记"这条请求是谁提的（以及他不能批自己的）"。少掉它，这三件都无法回答。
 *
 * <p>它是内置在进程里的极简身份，不是登录系统的产物：本项目不做认证体系，Principal 由通道
 * 解析入站消息后交给上层（ADR-0009 的边界）。
 *
 * @param userId 通道侧的用户标识
 */
public record Principal(String userId) {

    public Principal {
        Objects.requireNonNull(userId, "userId");
    }
}
