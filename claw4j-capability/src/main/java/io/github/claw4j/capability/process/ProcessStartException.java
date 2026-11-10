package io.github.claw4j.capability.process;

/**
 * 进程没起来：程序不存在、没有可执行位，或第一行不是 shebang。
 *
 * <p>与"跑了但没跑出结果"分开，理由与 {@link ProcessOutcome} 那两个字段一致：这两种失败给模型
 * 的信息完全不同。前者的修法是改入口脚本本身，后者是改脚本做的事，混成一句"执行失败"，
 * 模型没有任何东西可改。
 */
public class ProcessStartException extends RuntimeException {

    /**
     * 用一句说明建异常。
     *
     * @param message 哪个程序、为什么起不来
     */
    public ProcessStartException(String message) {
        super(message);
    }

    /**
     * 用一句说明与一个底层原因建异常。
     *
     * @param message 哪个程序、为什么起不来
     * @param cause 起进程或读入口时的底层异常
     */
    public ProcessStartException(String message, Throwable cause) {
        super(message, cause);
    }
}
