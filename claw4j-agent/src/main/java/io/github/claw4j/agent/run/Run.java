package io.github.claw4j.agent.run;

import io.github.claw4j.domain.NotifyPolicy;

import java.time.Instant;

/**
 * 一次执行：一件"事"在系统里的身份（ADR-0016）。
 *
 * <p>与会话的区别要说清，因为两者都在库里、都归一个人：
 * <b>会话是历史文本，run 是这件事做完了没有</b>。一个人可以同时有多件事
 * （一条 web 对话 + 一个每周定时任务），所以 session 与 run 是<b>一对多</b>。
 *
 * @param owner            归属键（{@code userId@权限指纹}），与会话表同一个口径
 * @param principalDigest  权限指纹，单独存一列：审计（P07）要能问"当时是按哪份权限跑的"
 * @param goal             这件事要干什么。存的是入站脱敏之后的文本，
 *                          与会话表那条口径一致——脱敏发生在落库之前，不是事后替换
 * @param status           状态机见 {@link RunStatus}
 * @param notifyPolicy     通知策略（A05）：做完之后要不要主动去找人。
 *                          它是这件事的属性，不是通道的属性——
 *                          同一个人可以盯着一件事、同时让另一件静默地跑
 * @param spent            已经用掉的量（三项，按 run 累计，A04）。
 *                          停在 {@link RunStatus#BUDGET_EXHAUSTED} 时它是"为什么停"的答案
 * @param result           到目前为止的回答<b>全文</b>（A05），每轮覆盖。
 *                          step 上的 {@code result_summary} 是截断摘要，
 *                          而查结果的人要看的是完整答案——两件事，两种长度
 */
public record Run(String id,
                  String owner,
                  String principalDigest,
                  String goal,
                  RunStatus status,
                  NotifyPolicy notifyPolicy,
                  Budget spent,
                  String result,
                  Instant createdAt) {

    public Run {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("run id 不能为空");
        }
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("run 的 owner 不能为空：空了等于所有人共享一件事");
        }
        // 没点名的按默认档走：把 null 留在这里会让"要不要通知"的判空散落到每个读的地方，
        // 而少判一处的表现是"这件事忽然不通知了"，那是事后才看得出来的错。
        notifyPolicy = notifyPolicy == null ? NotifyPolicy.DEFAULT : notifyPolicy;
    }
}
