package io.github.claw4j.channel.spi;

/**
 * 通道认人失败。语义是"这一条请求不该进来"，而不是"处理过程中出错"，
 * 所以控制器把它映射成 401 而不是 500。
 */
public class PrincipalRejectedException extends RuntimeException {

    private final String reason;

    public PrincipalRejectedException(String reason) {
        super(reason);
        this.reason = reason;
    }

    /**
     * 给客户端看的理由。刻意只放"缺什么"，不放"系统里有哪些角色"这类信息。
     */
    public String reason() {
        return reason;
    }
}
