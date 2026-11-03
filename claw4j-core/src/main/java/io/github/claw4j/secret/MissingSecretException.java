package io.github.claw4j.secret;

/**
 * "配了句柄，但环境里没有真值"——启动期就该出现，而不是第 200 次请求时（P08）。
 *
 * <p>消息里只有<b>名字</b>：句柄名、环境变量名、配置项名。没有值，也没有值的前缀或后四位——
 * 一段失败日志会进工单、进聊天记录，那是最容易把凭据搬出去的一条路。
 *
 * <p>继承 {@link IllegalStateException} 而不是自定义受检异常：它的出现意味着"这个部署没配好"，
 * 调用方除了让启动失败没有别的处理方式，写一个 {@code catch} 只会让失败推迟到更晚。
 */
public final class MissingSecretException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final String handle;

    public MissingSecretException(String name, String where) {
        super("句柄 " + SecretValue.SCHEME + name + " 取不到真值：环境变量 "
                + SecretResolver.environmentName(name) + " 不存在或为空。"
                + "配置项 " + where + "。"
                + "真值不进配置与示例——把它放进环境变量（或当地 .env），配置里只留句柄。");
        this.handle = SecretValue.SCHEME + name;
    }

    /** 出问题的句柄，形如 {@code secret:deepseek_api_key}。只有名字。 */
    public String handle() {
        return handle;
    }
}
