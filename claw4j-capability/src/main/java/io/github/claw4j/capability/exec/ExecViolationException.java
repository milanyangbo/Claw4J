package io.github.claw4j.capability.exec;

/**
 * 这次执行没通过 exec 的边界：身份、白名单、环境。
 *
 * <p>与"命令跑了但失败"分开：拒绝时进程根本没起来，模型该换一条命令而不是改参数重试。
 */
public class ExecViolationException extends RuntimeException {

    /**
     * 用一句说明建异常。
     *
     * @param message 拦下的是哪一条边界、拿到的值是什么
     */
    public ExecViolationException(String message) {
        super(message);
    }

    /**
     * 用一句说明与一个底层原因建异常。
     *
     * @param message 拦下的是哪一条边界、拿到的值是什么
     * @param cause 触发这次判定的底层异常
     */
    public ExecViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
