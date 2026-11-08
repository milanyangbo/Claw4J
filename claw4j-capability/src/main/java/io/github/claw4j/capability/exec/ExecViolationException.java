package io.github.claw4j.capability.exec;

/** 这次执行没通过 {@code exec} 的边界（目录、环境、超时、白名单、root）。 */
public class ExecViolationException extends RuntimeException {

    public ExecViolationException(String message) {
        super(message);
    }

    public ExecViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
