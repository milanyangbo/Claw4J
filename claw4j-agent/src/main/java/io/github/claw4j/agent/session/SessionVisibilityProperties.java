package io.github.claw4j.agent.session;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话可见性：{@code claw4j.tools.sessions.visibility}（票 05）。
 *
 * <p>前缀挂在 {@code claw4j.tools} 下面，与 OpenClaw 的 {@code tools.sessions.visibility} 同位置。
 * 同一个前缀下的兄弟键（{@code profile} / {@code allow} / {@code deny} / {@code exec}）不受影响：
 * {@link io.github.claw4j.agent.ToolsProperties} 是构造器绑定，未知子键一律忽略，两棵子树各绑各的。
 *
 * <p>字段是 String 而不是枚举：合法值只有四个，写错了要让启动失败并点名这四个。直接写枚举时，
 * 绑定层给的消息是"没有这个常量"，运维得去翻文档才知道能填什么；而这里写错的后果是授权范围，
 * 消息必须自己说清。校验落在紧凑构造器里，于是失败发生在绑定阶段，不是第一次召回时。
 *
 * <p>默认 {@code all}，代价写在明处（见 {@link SessionVisibility}）：多用户、角色不同的部署必须
 * 显式配 {@code self}，这一条写进 application.yml 的注释与 README。OpenClaw 自带的检查是
 * "两个以上 agent 且解析成 all"时报一条 finding（{@code docs/cli/security.md:39}），本仓库只有
 * 一个 agent 命名空间，那条永远不触发，所以改用启动日志兜底。
 *
 * @param visibility 四个值之一；缺键落 {@code all}
 */
@ConfigurationProperties("claw4j.tools.sessions")
public record SessionVisibilityProperties(String visibility) {

    public SessionVisibilityProperties {
        visibility = SessionVisibility.of(visibility).key();
    }

    /** 生效范围。构造时已经验过一遍，这里是读的时候再解析一次（值来自 record，不会变）。 */
    public SessionVisibility scope() {
        return SessionVisibility.of(visibility);
    }

    /** 全默认：{@code all}。 */
    public static SessionVisibilityProperties defaults() {
        return new SessionVisibilityProperties(null);
    }
}
