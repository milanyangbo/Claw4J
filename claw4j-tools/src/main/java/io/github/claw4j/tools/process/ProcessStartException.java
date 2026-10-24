package io.github.claw4j.tools.process;

/**
 * 进程没起来：程序不存在、没有可执行位、或者第一行不是 shebang。
 *
 * <p>与"跑了但没跑出结果"分开，理由和 {@code ProcessOutcome} 那条一样：
 * 这两种失败给模型的信息完全不同。前者的修法是"改入口脚本本身"，
 * 后者是"改脚本做的事"，混成一句"执行失败"，模型没有任何东西可改。
 */
public class ProcessStartException extends RuntimeException {

    public ProcessStartException(String message) {
        super(message);
    }

    public ProcessStartException(String message, Throwable cause) {
        super(message, cause);
    }
}
