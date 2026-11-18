package io.github.claw4j.capability.sandbox;

/**
 * 沙箱本该隔离住、结果没做到时抛这个。
 *
 * <p>它与"命令跑了但失败"不是一回事：那个交给模型重试，这个说明部署配错了，重试一百次也一样。
 * 所以它在**启动期**抛出，让应用起不来——配了 {@code backend=docker} 却静默回落到宿主，
 * 是这一层最坏的失效方式：用户以为有隔离，实际没有，而且要等到出事才知道。
 */
public class SandboxUnavailableException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * 建一个"沙箱不可用"的失败。
     *
     * @param message 说明哪一项探测失败、以及怎么修
     */
    public SandboxUnavailableException(String message) {
        super(message);
    }

    /**
     * 建一个"沙箱不可用"的失败，带上底层原因。
     *
     * @param message 说明哪一项探测失败、以及怎么修
     * @param cause 底层原因
     */
    public SandboxUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
