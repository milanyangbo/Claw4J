package io.github.claw4j.agent.approval;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 审批的两条策略（P02、P03）：等多久、谁能批。
 *
 * <p>过期点是策略，写在代码与配置里，不写在表上：表上放一个 {@code default now() + 10min} 会让
 * 「改了配置但库里还是十分钟」这种偏差看不见。写进配置、由应用算，改一处就真的改了一处
 * （{@code V6__run.sql} 里 {@code expires_at} 那条注释）。
 *
 * <p>十分钟是「够一个人看见并点一下」的量：太短则审批人开会回来时卡片已经作废，人会开始要求刷新而
 * 不是点按钮；太长则一条被忘掉的请求三天后还能放行，而三天前的上下文早不是现在要干的事。真需要跨时区
 * 审批时把它调大，但那是刻意的决定，不是默认。
 *
 * <p>审批人名单（P03）与业务角色是两张表：这里写的是「谁愿意为别人的操作负责」，
 * {@code claw4j.channel.http.roles} 写的是「谁能读哪些表」。挂到角色上的话，任何一次提权
 * （给某人加 {@code admin}）都会静默扩大审批面。
 *
 * <p>默认空等于没人能批，方向与"没有审批设施就没人能批"一致：忘配置时卡住，不是谁都能点
 * 头。代价是忘配置时所有待批请求都会过期，所以装配时会在日志里说一声（见
 * {@code JdbcApprovals} 的构造函数）。
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

    /**
     * 哪些工具要先经人批准（{@code Principal#userId()} 之外的维度：按工具名）。
     *
     * <p>默认空：随包发出的工具一个都不声明"要审批"。exec 不走这一条——它按
     * {@code security × ask} 在执行那一刻算，一条命令一判，按工具名一刀切表达不了
     * "白名单命中就放行"。MCP 工具或插件要人批时写在这里。
     */
    private List<String> requiredTools = new ArrayList<>();

    /** 过期清扫多久跑一次。默认一分钟；写 0 会变成热循环，所以非法值由调度器自己拒绝。 */
    private Duration sweepInterval = Duration.ofMinutes(1);

    /**
     * 一条请求等多长，默认十分钟。
     *
     * @return 有效期
     */
    public Duration getTtl() {
        return ttl;
    }

    /**
     * 设置有效期。过期边界由应用写入 {@code expires_at}，不依赖表上的默认值。
     *
     * @param ttl 有效期
     */
    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    /**
     * 能批的人。
     *
     * @return 审批人名单
     */
    public List<String> getApprovers() {
        return approvers;
    }

    /**
     * 设置审批人名单。传 null 等于清空。
     *
     * @param approvers 审批人名单
     */
    public void setApprovers(List<String> approvers) {
        this.approvers = approvers == null ? new ArrayList<>() : new ArrayList<>(approvers);
    }

    /**
     * 哪些工具要先经人批准。
     *
     * @return 工具名清单
     */
    public List<String> getRequiredTools() {
        return requiredTools;
    }

    /**
     * 设置要审批的工具名。传 null 等于清空。
     *
     * @param requiredTools 工具名清单
     */
    public void setRequiredTools(List<String> requiredTools) {
        this.requiredTools = requiredTools == null ? new ArrayList<>() : new ArrayList<>(requiredTools);
    }

    /**
     * 过期清扫间隔。
     *
     * @return 间隔
     */
    public Duration getSweepInterval() {
        return sweepInterval;
    }

    /**
     * 设置过期清扫间隔。
     *
     * @param sweepInterval 间隔
     */
    public void setSweepInterval(Duration sweepInterval) {
        this.sweepInterval = sweepInterval == null ? Duration.ofMinutes(1) : sweepInterval;
    }
}
