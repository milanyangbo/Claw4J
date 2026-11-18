package io.github.claw4j.capability.exec;

import io.github.claw4j.domain.principal.Principal;

/**
 * 这一次工具调用是**谁**在问、属于哪一段对话：exec 需要它来落一条审批请求（票 05）。
 *
 * <p>为什么要有它：exec 的判定发生在 {@code ExecBackend.run(ExecSpec)} 里，而那一刻手边只有
 * 命令、目录与超时。审批需要的是发起人（批给谁、不能自批）与来处（结论投回哪段对话），这些只在
 * 框架的 {@code ToolContext} 上有。所以由 {@link ExecContextToolCallback} 在调用前后把这几样
 * 放进本类，端口读它。
 *
 * <p>为什么是线程本地：工具回调与它委派的后端**在同一次同步调用里**跑，不存在换线程；而"每轮
 * 造一份后端"要求重建库的 {@code ShellTools}，我们改不了它。
 *
 * <p>取不到时**不是**退回某个默认身份：没有身份的调用一律由端口拒绝。给一个默认值在这里是最危险
 * 的写法——它让"上下文没接上"表现成"某个人批过"，而那个人根本不存在。
 *
 * @param principal  发起人，必填
 * @param sessionKey 这一轮属于哪段对话，可以为 {@code null}
 * @param channel    卡片往哪个通道发，可以为 {@code null}
 * @param chatRef    通道内的会话地址，可以为 {@code null}
 * @param runId      这一轮是哪一轮，可以为 {@code null}
 */
public record ExecCallContext(Principal principal, String sessionKey, String channel, String chatRef,
                              String runId) {

    /** 当前线程上这一次工具调用的上下文。没有绑定时是 {@code null}，表示"这次调用没有身份"。 */
    private static final ThreadLocal<ExecCallContext> CURRENT = new ThreadLocal<>();

    public ExecCallContext {
        java.util.Objects.requireNonNull(principal, "principal");
    }

    /**
     * 发起人的 {@code userId}。审批行的 {@code owner}、兑现与自批判定都用它。
     *
     * @return 用户标识
     */
    public String owner() {
        return principal.userId();
    }

    /**
     * 绑上，跑完还原成上一个值。
     *
     * <p>还原而不是无条件 {@code remove()}：委派是同步的，子 Agent 的调用可能嵌套在同一线程上，
     * 清空会让外层后续的调用变成"没有身份"。
     *
     * <p>公开是因为它是这个类唯一的用法：工具回调那一层绑、端口那一层读。测试也要照这条路走——
     * 直接 {@code set} 一个上下文再断言，验的就不是生产路径。
     *
     * @param context 这一次的上下文，可以为 {@code null}（这一次不绑）
     * @param body    要跑的那段调用
     * @param <T>     返回类型
     * @return body 的返回值
     */
    public static <T> T with(ExecCallContext context, java.util.function.Supplier<T> body) {
        ExecCallContext previous = CURRENT.get();
        if (context == null) {
            return body.get();
        }
        CURRENT.set(context);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * 当前线程上这一次调用的上下文。
     *
     * @return 上下文；没绑定时为 {@code null}
     */
    public static ExecCallContext current() {
        return CURRENT.get();
    }
}
