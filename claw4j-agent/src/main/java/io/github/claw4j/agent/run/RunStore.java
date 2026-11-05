package io.github.claw4j.agent.run;

import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.principal.Principal;

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
     */
    Optional<Run> start(String owner, String principalDigest, String goal, NotifyPolicy notifyPolicy);

    /**
     * 记下这件事是谁发起的（P02）。批准之后服务端续跑，用的就是这份身份。
     *
     * <p><b>为什么不并进 {@link #start}</b>：它是"这件事可能被服务端叫醒"的前提条件，
     * 而不是"开一件事"的必备部分——定时任务、子 Agent、以及不接审批的部署都不需要它。
     * 并进 start 会让每个调用点都要先造一个 Principal，而那些地方的 run 本来就不归人。
     *
     * <p><b>没记下来的后果是明确的</b>：这件事不会被服务端续跑
     * （{@link #principalOf} 读不到，`ApprovalService` 会如实说"让提请求的人再说一句继续"）。
     * 与其拿一个猜来的身份去跑，不如停在原地。
     *
     * @param runId     哪一件事
     * @param principal 发起人。整份存下来——只存指纹反推不出角色与可访问表
     */
    void attachPrincipal(String runId, Principal principal);

    /**
     * 记下这条 run 属于哪一段会话（ADR-0025）。
     *
     * <p><b>单独一条 update，不并进 {@link #start}</b>：会话键由通道与 chatId 派生，
     * 那是"这一轮从哪来"的信息，与"这件事是什么"分开更清楚；
     * 而且并进 start 会让定时任务、子 run 这些没有会话的调用点被迫造一个假键。
     *
     * <p>没记下来的后果：这条 run 不参与会话预算累计，退回按它自己那一行算——
     * 也就是改之前的行为。少算一段，不拦错人。
     */
    default void attachSession(String runId, String sessionId) {
        // 默认什么都不做：这条 run 就不会参与会话累计，退回按它自己那一行算——
        // 也就是改动之前的行为。故意不抛异常：替身与降级部署都靠这条默认路径，
        // 而"少算一段"比"这一轮直接失败"轻。
    }

    /**
     * 记下这条 run 的"对话地址"（ADR-0026 决定二）。
     *
     * <p><b>与 {@link #attachSession} 是两件事</b>：那个记"哪一段会话"
     * （{@code claw4j:user:…:<段>}，会随段轮换而变，给记忆与预算用）；
     * 这个记"哪条对话"（{@code addr:<owner>|<通道>|<chatId>}，不随段变，用来抢 lane 那把锁）。
     *
     * <p><b>为什么要多存一份</b>：审批续跑没有入站消息，手上只有 {@code session_id}
     * 那一串。反解要按 {@code :} 切，而 {@code chatId} 里出现冒号是可能的——
     * 解析错的方式是"锁到了另一条对话上"，不报错。
     */
    default void attachAddress(String runId, String address) {
        // 默认什么都不做：这条 run 就不会在续跑时抢锁，退回"各跑各的"。
        // 替身与降级部署靠这条默认路径，而"少了串行化"比"这一轮直接失败"轻。
    }

    /**
     * 这条 run 当初在哪条对话里。空表示没记下来（加列之前的老数据、存储降级）。
     *
     * <p>空的时候续跑<b>不加锁</b>：那条路上只有审批能推动它，而"放弃执行"
     * 比"上一把抢不到的锁"糟得多——前者是永远不做，后者只是这一次没排上。
     */
    default Optional<String> sessionAddressOf(String runId) {
        return Optional.empty();
    }

    /**
     * 这条 run 属于哪一段会话。空表示没记下来（加列之前的老数据、存储降级）。
     *
     * <p>审批续跑那条路上没有入站消息，算不出会话键，只能回读——
     * 续跑的结果要写回"当初提问的那段对话"，否则人点完批准，答案落到别处去了。
     */
    default Optional<String> sessionOf(String runId) {
        return Optional.empty();
    }

    /**
     * 接上一个已经存在的 run：不新建，序号接着涨（A02）。
     *
     * <p>返回空表示"接不上"，三种情况不作区分，因为调用方的动作一样（另开一个）：
     * 没有这个 run、它不属于 {@code owner}、它正在等审批。
     *
     * <p><b>第三种必须挡住</b>：等审批的 run 只能由审批放行（P02），
     * 一句"继续"就能把它唤醒的话，那道门等于没装。
     *
     * @param owner 归属键，与 {@link #start} 同一个口径。带它才能挡住"接别人的 run"
     */
    Optional<Run> resume(String runId, String owner);

    /**
     * 审批放行之后由服务端续跑（P02）：这里能把 {@link RunStatus#SUSPENDED} 拉回 running。
     *
     * <p><b>为什么与 {@link #resume} 分开而不是加个开关</b>：那两种唤醒的来源根本不同——
     * 一个是"用户又说了一句继续"，一个是"有人点了批准"。合成一个方法之后，
     * {@code resume} 里那句"等审批的 run 只能由审批放行"就变成一个条件参数，
     * 而条件参数写错一次，那句约束就永久消失且没有任何测试会红。
     *
     * <p><b>只从 suspended 转</b>：已经做完的事不该因为一条迟到的批准被拉起来重跑。
     *
     * @return 空表示接不上（没有这个 run、不在 suspended、存储降级）。
     *         三种都不细分的理由与 {@link #resume} 相同
     */
    Optional<Run> reopen(String runId);

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

    /**
     * 停在这里等人批（P02）。只改状态，不建新行、不删任何东西。
     *
     * <p><b>"停在这一步"是库里的一行状态，不是挂着的线程</b>：请求照常返回，
     * 审批号回给用户，服务端一个线程都不占。挂线程的写法在这一层有三个躲不掉的问题——
     * 审批可能十分钟后才来（线程早被池子收走）、进程重启后没人记得它在等（线程没了）、
     * 一件被忘掉的事会一直吃掉一个线程。
     *
     * <p>只从 {@link RunStatus#RUNNING} 转（SQL 里带着这个条件）：
     * 已经做完的事不该因为一条迟到一小时的审批被拉回 suspended。
     */
    void suspend(String runId);

    /**
     * 这件事是谁发起的（P02）。批准之后服务端续跑，用的就是这份身份。
     *
     * <p>空表示读不到（存储降级，或者这个 run 早于 P02、没有快照）。
     * 那种情况下**不续跑**：拿一个猜来的身份去跑，比让它停在原地更糟。
     */
    Optional<Principal> principalOf(String runId);

    /**
     * 把等审批的那一步改成结论（P02）。
     *
     * <p>不这么做的话，recap 里那一步会一直显示"等审批"，而 resume 时模型看到的
     * 正是 recap——它会以为还在等，于是把"等人批"这件事再说一遍。
     *
     * <p><b>不用传 seq</b>：run 停在 suspended 之后不会再产生新的审批，
     * 所以这个 run 里状态为 awaiting 的 approval step 最多一条。
     * 按 seq 追反而要先把序号存回审批行，多一次写、多一处能忘。
     *
     * @param status {@link StepStatus#DONE}（批了）或 {@link StepStatus#FAILED}（拒了）
     */
    void settleApprovalStep(String runId, StepStatus status);

    /** 回读一个 run。用于"这件事做到第几步"与进程重启之后接着问。 */
    Optional<Run> find(String runId);

    /** 一个 run 的全部 step，按序号升序。 */
    List<Step> stepsOf(String runId);

    /**
     * 这个 run <b>当前这版</b>的计划，按计划内顺序（seq 升序）。
     * 没有计划时是空列表，不是 null——"没有计划"是常态，不是异常。
     *
     * <p><b>只返回当前这一版</b>：计划被重写之后旧的那版仍然在库里（审计要能回答
     * "当时打算怎么干"），但"还剩什么没做"只能有一个答案。
     * 哪一版是当前的由 {@code agent_run.plan_ref} 记着（V7 的注释）。
     */
    List<Step> planOf(String runId);

    /**
     * 整体重写这个 run 的计划。
     *
     * <p><b>重写而不是追加</b>：模型改主意时给的是"现在打算这么做的完整清单"。
     * 追加两份清单的后果是"还剩什么没做"读到两个答案，
     * 而那两个答案看起来都对——这正是"计划放在上下文里"会出的那个错的库上版本。
     *
     * <p><b>旧的那版不删</b>：删掉的话审计就少了一段"当时打算怎么干"，
     * 而这张表存在的理由之一就是能回答这个问题（P07 的审计投影读的就是它）。
     * 旧版只是不再被 {@link #planOf} 返回。
     *
     * @param items 按顺序写的每一步；调用方保证非空、无空白项
     * @return 写入后的这版计划。空表示没记下来（与 {@link #append} 同一个降级口径）
     */
    Optional<List<Step>> setPlan(String runId, List<String> items);

    /**
     * 这个 run 已经用掉的量（A04）：工具调用次数、token、耗时三项。
     *
     * <p><b>空表示"读不到"，不表示"没用掉"</b>：没有这个 run、或者存储降级，
     * 都是空。调用方（{@link RunBudget}）对两者的动作一样——管不住就不拦。
     * 用 {@link Budget#NONE} 去表示"读不到"会把"少记一笔"说成"一点没花"，
     * 而那正是预算最怕听见的那种话。
     *
     * <p><b>读的是"这段会话"的累计，不是这一条 run</b>（ADR-0025）：run 活一轮，
     * 按它记的数每轮都从 0 开始。所以先由 run 反查它属于哪段会话，再取那段会话
     * 在窗口内的累计。{@code idleBefore} 之前没动过的会话视为已结束，计数清零——
     * 这就是"空闲即新会话"，与 OpenClaw 的 idle reset 同构。
     *
     * @param idleBefore 窗口起点（现在减去空闲时长）。早于它的活动不算同一段对话
     */
    Optional<Budget> spent(String runId, Instant idleBefore);

    /**
     * 往上加一笔用量（A04）。三项各自累加，不传绝对值——
     * 调用方算总数再写回会在并发下互相覆盖，而覆盖的表现是"额度凭空多出来"。
     *
     * @return 加完之后的用量。空表示没记下来（与 {@link #append} 同一个降级口径）
     */
    Optional<Budget> charge(String runId, int toolCalls, long tokens, long millis);

    /**
     * 把计划里的第 {@code ordinal} 步改成 {@code status}。
     *
     * <p><b>序号是计划内序号（从 1 开始），不是 {@code seq}</b>：计划被重写过之后
     * 它的 seq 从当时那一步接着往下走，而模型手里的"第 2 步"指的是清单里的第二个。
     * 让它去猜落库序号，等于把编号这件事推回给自然语言。
     *
     * @return 改后的那一步。没有这一步（序号越界）、或没记下来，都是空——
     *         调用方对两者的动作一样（告诉模型"没能标上"）
     */
    Optional<Step> markPlan(String runId, int ordinal, StepStatus status);
}
