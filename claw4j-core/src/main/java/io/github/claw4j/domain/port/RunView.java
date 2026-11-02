package io.github.claw4j.domain.port;

import io.github.claw4j.domain.NotifyPolicy;

import java.util.List;

/**
 * 一件事"现在是什么形状"：给通道读的那一版（ADR-0018）。
 *
 * <p>它存在是因为读的人和写的人不在同一个模块：run 与 step 落在
 * {@code claw4j_run} 里（那是 Agent 模块的事），而 {@code GET /api/runs/{id}}
 * 挂在通道模块。通道不许反向依赖 Agent（ADR-0010），于是要有一个
 * <b>core 里的读法</b>——本记录就是它，{@link RunQuery} 是取它的那个口子。
 *
 * <p><b>状态是字符串，不是枚举</b>：{@code RunStatus} 住在 Agent 模块，
 * 把它搬下来会让"加一个状态"变成跨两个模块的改动。这里与库里那一列同口径
 * （{@code running / suspended / done / failed / budget_exhausted}），
 * 读的人拿它显示、拿它判"还有没有下文"，不需要枚举的方法。
 *
 * @param runId        这件事的 id，客户端拿它来续问与查结果
 * @param goal         当时要干什么
 * @param status       状态，与 {@code agent_run.status} 同口径
 * @param result       <b>回答全文</b>，不是摘要。step 上的 {@code result_summary}
 *                     是截断的（它的用途是"这一步得到了什么"），
 *                     而人要看的是完整答案——两件事，两种长度
 * @param notifyPolicy 这一件事的通知策略：告诉查的人"没收到推送是正常的还是丢了"
 * @param steps        按顺序的全过程，供人核对自己问的那件事走到哪一步
 */
public record RunView(String runId,
                      String goal,
                      String status,
                      String result,
                      NotifyPolicy notifyPolicy,
                      List<Step> steps) {

    /**
     * 一步的形状。
     *
     * <p>{@code kind} 与 {@code status} 同样取列值字符串（{@code model_turn / plan …}），
     * 理由与外层 {@code status} 一样：枚举住在 Agent 模块。
     */
    public record Step(int seq, String kind, String status, String summary) {
    }
}
