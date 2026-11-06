package io.github.claw4j.agent.run;

import io.github.claw4j.domain.NotifyPolicy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * run 与 step 的落点。
 *
 * <p><b>每个写方法都返回 {@code Optional} 而不是对象</b>，因为"这件事没记下来"是一种
 * 真实会发生的结果，不是异常：库没迁、表没建、连接断了，都不该把一次提问打成 500
 * （与会话记忆同一个口径，见 {@code FaultTolerantSessionService}）。
 * 返回空值让调用方明写"没有 run"这条分支，而不是靠 catch 到处兜。
 *
 * <p><b>代价要记账</b>：落不下去时审计会缺一行。缺多少由 {@code claw4j.run.errors}
 * 指标暴露，别让"降级"变成"永远没人知道记没记上"。
 */
public interface RunStore {

    /** 开一个 run，通知策略走默认档 {@link NotifyPolicy#DEFAULT}。 */
    default Optional<Run> start(String owner, String principalDigest, String goal) {
        return start(owner, principalDigest, goal, NotifyPolicy.DEFAULT);
    }

    /**
     * 开一个 run，并定下它的通知策略（A05）。
     *
     * <p><b>策略在开的时候定，不事后改</b>：起头的人知道这是一件"盯着看的"还是
     * "跑完放着"的事，那是唯一说得清的时刻。事后改要回答"谁来改、改哪一段"，
     * 而那两个问题在这件事上没有好答案。
     *
     * @param notifyPolicy 做完之后要不要主动去找人；定时任务传
     *                     {@link NotifyPolicy#SILENT}
     * @param sessionId    这一轮属于哪一段会话（ADR-0025）。预算按会话累计就靠它：
     *                     空表示按这一行自己算，也就是"每轮 6 次"的旧口径。
     *                     <b>开的时候就写进去</b>，不事后补一条 update——补写的那一下
     *                     一旦失败，这一轮的额度就悄悄变成按 run 算，而没有任何报错
     */
    Optional<Run> start(String owner, String principalDigest, String goal,
                        NotifyPolicy notifyPolicy, String sessionId);

    /** 不带会话键的版本：子 Agent 与单测走这里，它们不参与会话预算。 */
    default Optional<Run> start(String owner, String principalDigest, String goal,
                                NotifyPolicy notifyPolicy) {
        return start(owner, principalDigest, goal, notifyPolicy, null);
    }

    /** 追加一步，序号由存储分配。返回空表示没记下来。 */
    Optional<Step> append(StepInput input);

    /** 收尾，回答全文不变（失败与"额度用尽"那两条路没有新结果可写）。 */
    default void finish(String runId, RunStatus status) {
        finish(runId, status, null);
    }

    /**
     * 收尾，并把这一轮的回答全文记到 run 上（A05）。
     *
     * <p><b>全文记在 run 上而不是只留在 step 的摘要里</b>：摘要是给"这一步得到了什么"
     * 看的，截断过；查结果的人要的是完整答案。记在这里，定时任务与审批续跑的结果
     * 才有地方被取走——那两种场景里没有一次请求在等着接它。
     *
     * @param result 这一轮的回答全文。null 表示"不改"（失败路径没有结果可写）
     */
    void finish(String runId, RunStatus status, String result);

    /** 回读一个 run。用于"上一轮是什么结局"与审计对账。 */
    Optional<Run> find(String runId);

    /** 一个 run 的全部 step，按序号升序。 */
    List<Step> stepsOf(String runId);

}
