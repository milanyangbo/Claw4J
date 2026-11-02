package io.github.claw4j.agent.approval;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 审批的两条策略（P02、P03）：等多久、谁能批。
 *
 * <p><b>过期点是策略，写在代码/配置里，不写在表上</b>：表上一个 {@code default now() + 10min}
 * 会让"改了配置但库里还是十分钟"这种偏差看不见——写进配置、由应用算，
 * 改一处就真的改了一处（V10 的注释里那条）。
 *
 * <p><b>十分钟这个默认值是"够一个人看见并点一下"</b>：太短则审批人开会回来时卡片已经作废，
 * 人会开始要求刷新而不是点按钮；太长则一条被忘掉的请求三天后还能放行，
 * 而三天前的上下文早就不是现在要干的事了。真需要更长的场景（跨时区审批）把它调大，
 * 但那是刻意的决定，不是默认。
 *
 * <p><b>审批人名单（P03）与业务角色是两张表</b>：这里写的是"谁愿意为别人的操作负责"，
 * {@code claw4j.channel.http.roles} 写的是"谁能读哪些表"。挂到角色上的话，任何一次提权
 * （给某人加 {@code admin}）都会静默扩大审批面，而审批面从来不该是"顺便"变大的东西。
 *
 * <p><b>默认空 = 没人能批</b>，方向与 {@code ApprovalGate#denyAll()} 一致：忘配置时卡住，
 * 不是"谁都能点头"。代价是忘配置时所有待批请求都会过期，所以装配时会在日志里说一声
 * （见 {@code ApprovalService} 的构造函数）。
 */
@ConfigurationProperties(prefix = "claw4j.approval")
public class ApprovalProperties {

    /** 一条请求等多长。默认十分钟。 */
    private Duration ttl = Duration.ofMinutes(10);

    /**
     * 能批的人（{@code Principal#userId()}）。飞书那一侧写 {@code open_id}，HTTP 那一侧写用户名。
     *
     * <p>写 role 名是无效的：名单按人比，不按角色比——理由见类注释。
     */
    private List<String> approvers = new ArrayList<>();

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public List<String> getApprovers() {
        return approvers;
    }

    public void setApprovers(List<String> approvers) {
        this.approvers = approvers == null ? new ArrayList<>() : new ArrayList<>(approvers);
    }
}
