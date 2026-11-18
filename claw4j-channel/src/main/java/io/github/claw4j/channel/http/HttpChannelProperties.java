package io.github.claw4j.channel.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * HTTP 通道的配置：谁能进来、一次流式最长多久。
 *
 * <p>这里<b>没有角色、也没有授权表</b>（ADR-0030 裁决三）。原来 {@code roles.<角色>} 里写着
 * "能读哪些表、能跑哪些动作"，那套授权随权限登记表整块下线：权限现在只有
 * 大模型这一层——看得见什么由工具清单决定，执行前要不要人点头由审批决定。业务数据范围与
 * 按人的细粒度授权属于第二步。
 *
 * <p>留下来的是准入：{@code users} 是一份白名单，请求头里的用户标识必须在里面。它守的是
 * "任何人知道地址就能触发这个 agent"，与"这个人能看哪张表"不是同一个问题——前者是通道的职责，
 * 后者已经不在这一层。
 */
@ConfigurationProperties(prefix = "claw4j.channel.http")
public class HttpChannelProperties {

    /**
     * 谁能用这条通道。空的含义是<b>谁都不能</b>，不是"谁都能"：这条通道的前置条件是网关在边缘
     * 做过认证并把结果写成 {@code X-Claw4j-User}，而这台服务无从判断那个头是不是网关写的。
     * 默认拒绝至少保证"忘了配"表现为 403 而不是"公网可调用"。
     *
     * <p>本机演示因此要先把自己填进来（{@code claw4j.channel.http.users: [你的名字]}），
     * 否则 {@code curl} 拿到 403 并被告知缺哪一样。这个代价是刻意的：一份默认为空的名单，
     * 与 {@code claw4j.approval.approvers} 空名单=没人能批是同一个取向。
     */
    private Set<String> users = new LinkedHashSet<>();

    /**
     * 一次 SSE 连接的最长存活时间。超时即断开，避免客户端半途消失后连接吊着。
     */
    private Duration streamTimeout = Duration.ofSeconds(60);

    public Set<String> getUsers() {
        return users;
    }

    public void setUsers(Set<String> users) {
        this.users = users == null ? new LinkedHashSet<>() : new LinkedHashSet<>(users);
    }

    /**
     * 这个用户标识在不在白名单里。
     *
     * <p>通配只认一个字符 {@code *}（整条白名单就是 {@code ["*"]}）：那是显式写下的"谁都行"，
     * 与"忘了配"（空集合）在行为上必须能区分开。
     *
     * @param userId 请求头里的用户标识
     * @return 在名单里时为 {@code true}
     */
    public boolean allows(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        return users.contains("*") || users.contains(userId);
    }

    public Duration getStreamTimeout() {
        return streamTimeout;
    }

    public void setStreamTimeout(Duration streamTimeout) {
        this.streamTimeout = streamTimeout;
    }
}
