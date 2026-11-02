package io.github.claw4j.agent.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 审计留多久（P07）。
 *
 * <p><b>为什么是"整件旧事一起删"</b>：审计行就是 step，它的寿命与 run 一样长。
 * 单独删审计行、把 run 留着，会得到"有一件事，但查不到它做过什么"——
 * 那比删掉更难解释。反过来只删 run 呢？step 会跟着级联走，审计自然一起没了，
 * 所以这里只有一个数，作用于 run。
 *
 * <p><b>90 天是合规那一格常见的口径</b>（一个季度）：短于这个数，"上个季度谁批了什么"
 * 在季度复盘时就查不到了；长于它，库里的业务数据副本（摘要 200 字符）与
 * 会话记录一起变成没人清理的历史。
 *
 * <p><b>过期不是一种状态，是时间</b>（与审批那条同一个口径）：判定在 SQL 里用库的时钟比，
 * 不靠某个定时器把状态改掉。{@code AuditRetention} 只是把"已经过期的那些"删掉，
 * 删不掉也不影响任何查询的答案——失效的判定本来就不依赖它跑没跑。
 */
@ConfigurationProperties(prefix = "claw4j.audit")
public class AuditProperties {

    /** 一件旧事连同它的执行记录留多久。默认 90 天。 */
    private Duration retention = Duration.ofDays(90);

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    /**
     * 保留期的毫秒数。
     *
     * <p>写成"从配置算出毫秒、SQL 里用库的时钟减"而不是传一个绝对时刻：
     * 传绝对时刻的话，"90 天"就变成了"应用时钟的 90 天"，而库里那一列的默认值
     * 与所有比较用的都是库的时钟。
     */
    public long retentionMillis() {
        return retention == null ? Duration.ofDays(90).toMillis() : retention.toMillis();
    }
}
