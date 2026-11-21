package io.github.claw4j.model.failover;

/**
 * 一次模型调用失败的原因：17 项闭合词表，照抄 OpenClaw
 * （`packages/gateway-protocol/src/failover-reasons.ts:1-19`）。
 *
 * <p>为什么要一张闭合词表而不是几个布尔：冷却要按原因定时长（限流听供应商的、billing 十分钟起），
 * 指标要能回答"这次是限流还是 key 到期"，候选链要按原因决定"换一家有没有意义"。
 * 布尔只能回答"要不要换"，回答不了"为什么"。
 *
 * <p>字面量与它一字不差，不翻译、不合并：以后要对照它的文档与日志时，多一层翻译就多一处对不上。
 *
 * <p>{@link #TRANSIENT_SLOT} 与 {@link #KEEPS_PROBE_BUDGET} 两张集合也照抄它的
 * `src/agents/failover-policy.ts:12-45`：前者是"可以吃瞬时重试预算"的，后者是"失败但不要因此
 * 把探测预算花光"的。
 */
public enum FailoverReason {

    /** 鉴权失败（401）。 */
    auth,

    /** 凭据永久失效：不是这一把钥匙的问题，换一把也不行。 */
    auth_permanent,

    /** 拿到的内容格式不对（工具参数坏 JSON、响应体截断）。 */
    format,

    /** 限流（429）。冷却只认供应商给的时长。 */
    rate_limit,

    /** 过载（503）：服务在，但暂时接不了。 */
    overloaded,

    /** 欠费 / 额度用完（402，或 403 带额度文案）。 */
    billing,

    /** 服务端错误（500 / 502 / 504）。 */
    server_error,

    /** 连不上：DNS、连接被拒、路由不可达。 */
    route_unavailable,

    /** 超时。 */
    timeout,

    /** TLS 证书问题：整家 provider 都不能用，不是这一个模型的事。 */
    tls_certificate,

    /** 上下文溢出：请求比模型的窗口大。 */
    context_overflow,

    /** 模型不存在（404）：名字写错或这一家没有这个模型。 */
    model_not_found,

    /** 会话过期。本仓目前没有产生方（见 {@link #PRODUCER_GAPS}）。 */
    session_expired,

    /** 模型返回了空内容。 */
    empty_response,

    /** 失败里没有任何细节可判。 */
    no_error_details,

    /** 认不出来的失败：不猜，按不可重试处理。 */
    unclassified,

    /** 未知：留给上游显式标注（我们没有插件体系，分类器不产出它）。 */
    unknown;

    /**
     * 可以吃"瞬时重试预算"的原因。
     *
     * <p>照 OpenClaw 的 {@code TRANSIENT_FAILOVER_REASONS}。表里没有的那些（鉴权、格式、模型不存在…）
     * 重试也不会变好，消耗预算只会把一次确定的错误拖成 N 次往返。
     */
    public static final java.util.Set<FailoverReason> TRANSIENT_SLOT = java.util.EnumSet.of(
            rate_limit, overloaded, unknown, empty_response, no_error_details, unclassified, timeout);

    /**
     * 失败但**不应**把探测预算花光的原因：它们的共同点是"再探也是同一个答案"。
     *
     * <p>照 OpenClaw 的 {@code NON_TRANSIENT_KEEP_PROBE_REASONS}。
     */
    public static final java.util.Set<FailoverReason> KEEPS_PROBE_BUDGET = java.util.EnumSet.of(
            model_not_found, route_unavailable, format, auth, auth_permanent, session_expired,
            tls_certificate);

    /**
     * 目前没有产生方的原因：词表先留着，等真出现再补判据。
     *
     * <p>写出来是为了让"17 项都有归宿"成为一条可断言的规则——漏一项就会在
     * {@code everyReasonHasProducerOrGap} 里红，而不是悄悄躺在枚举里没人用。
     */
    public static final java.util.Set<FailoverReason> PRODUCER_GAPS = java.util.EnumSet.of(
            auth_permanent, session_expired, empty_response, no_error_details, unknown);

    /**
     * 同一个模型上再试一次有没有意义（重试预算用）。
     *
     * <p>与 {@link #worthTryingAnother()} 不是一回事：{@code route_unavailable} / {@code timeout}
     * 值得在同家重试（链路抖了一下），但换一家也对；{@code context_overflow} 换一家有意义、
     * 同家重试没意义（同一个窗口还是装不下）。
     *
     * @return 值得同家重试时为 true
     */
    public boolean retryable() {
        return switch (this) {
            case auth, auth_permanent, format, model_not_found, session_expired,
                 tls_certificate, billing, context_overflow -> false;
            default -> true;
        };
    }

    /**
     * 换另一家还有没有意义。
     *
     * <p>false 的只有四种：鉴权失败（换谁都要钥匙）、凭据永久失效、格式不对（同一份请求换一家
     * 也解析不了）、会话过期（换一家也还是过期）。其余的都值得换一家——包括
     * {@code context_overflow}（换窗口更大的）与 {@code model_not_found}（换有这个名字的）。
     *
     * @return 值得降级时为 true
     */
    public boolean worthTryingAnother() {
        return switch (this) {
            case auth, auth_permanent, format, session_expired -> false;
            default -> true;
        };
    }
}
