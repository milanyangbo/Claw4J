package io.github.claw4j.agent.audit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * 一轮失败了，但它是「哪种失败」（ADR-0037 票 01）。
 *
 * <p>补的是词表里空着的那四个终态：{@code cancelled} / {@code timed_out} 以前一律记成
 * {@code failed}，于是「这轮被取消了几次」「是不是模型太慢」在审计里问不出来——而这两句恰恰是
 * 出事时最先问的。OpenClaw 把这三件事分开记
 * （{@code src/audit/audit-event-types.ts:75-80}：{@code run_failed} / {@code run_cancelled} /
 * {@code run_timed_out}），这里对齐它。
 *
 * <p>判据只看异常链：这条链上没有别的信息可用（{@code ChatClient} 把什么都包成运行时异常）。
 * 三档的先后是有讲究的——
 *
 * <ol>
 *   <li><b>取消</b>：{@link InterruptedException} / {@link CancellationException} /
 *       线程的中断位。客户端断开时 {@code SseEmitter} 的 {@code onError}/{@code onCompletion}
 *       会 {@code cancel(true)}，中断落在 {@code blockLast()} 上，表现就是这一类。</li>
 *   <li><b>超时</b>：链上任何一层的类型名里带 {@code Timeout}
 *       （{@link TimeoutException}、{@code SocketTimeoutException}、Spring 的
 *       {@code AsyncRequestTimeoutException}）。放在取消之后：{@code SocketTimeoutException}
 *       也是 {@link IOException}，顺序反了它会被记成取消。</li>
 *   <li><b>客户端走了</b>：{@link IOException} / {@link UncheckedIOException}。流式这一路写回
 *       客户端时最常见的失败就是它（broken pipe），而那不是"模型坏了"。代价要认：模型侧的网络错误
 *       也长这个样子，会被记成取消——两者的处置都是「换个连接重来」，先合成一档。</li>
 *   <li>其余落 {@code failed / run_failed}。</li>
 * </ol>
 *
 * <p>只做运行级：工具级有 {@link ToolCallAudit#toolFinished} 那条路，它的失败来自工具自己抛的异常，
 * 与这里不是同一个问题域。
 *
 * @param status 结局，只会是 failed / cancelled / timed_out
 * @param errorCode 对应的归一化错误码
 */
public record RunOutcome(AuditStatus status, AuditErrorCode errorCode) {

    /** 不是取消、不是超时、不是客户端走了：按普通失败记。 */
    private static final RunOutcome FAILED = new RunOutcome(AuditStatus.FAILED, AuditErrorCode.RUN_FAILED);

    private static final RunOutcome CANCELLED =
            new RunOutcome(AuditStatus.CANCELLED, AuditErrorCode.RUN_CANCELLED);

    private static final RunOutcome TIMED_OUT =
            new RunOutcome(AuditStatus.TIMED_OUT, AuditErrorCode.RUN_TIMED_OUT);

    /**
     * 这一次失败该怎么记。
     *
     * @param failure 抛出来的东西，可为 null（那时只能记失败）
     * @return 结局与错误码
     */
    public static RunOutcome of(Throwable failure) {
        if (failure == null) {
            return FAILED;
        }
        boolean cancelled = false;
        boolean timedOut = false;
        boolean io = false;
        // 先把整条链看一遍再下结论：三档里"超时"最具体，"取消"最笼统——
        // 中断常常被包在 IOException 里，边走边下结论会把超时记成取消。
        for (Throwable current = failure; current != null && current != current.getCause();
                current = current.getCause()) {
            if (current instanceof InterruptedException || current instanceof CancellationException) {
                cancelled = true;
            }
            if (current.getClass().getSimpleName().contains("Timeout")) {
                timedOut = true;
            }
            if (current instanceof IOException || current instanceof UncheckedIOException) {
                io = true;
            }
        }
        if (timedOut) {
            return TIMED_OUT;
        }
        if (cancelled || io) {
            return CANCELLED;
        }
        return FAILED;
    }
}
