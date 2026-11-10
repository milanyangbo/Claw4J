package io.github.claw4j.channel.feishu.sdk;

import com.lark.oapi.ws.exception.ClientException;
import com.lark.oapi.ws.exception.ServerException;
import com.lark.oapi.ws.exception.ServerUnreachableException;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * 把「连不上」分成两类：换个时间点重试会不会好。
 *
 * <p>分法参考 OpenClaw 的 Socket Mode 策略：可恢复的启动、等待、断开失败
 * 「retry until the channel stops」，凭据类永久错「fail fast instead of retrying forever」
 * （{@code docs/channels/slack/transports.md:71}）。
 *
 * <p>下面每一条都按 lark-oapi 2.8.7 的字节码核过（{@code javap -p -c com.lark.oapi.ws.Client}），
 * 不是按异常名字猜的：
 *
 * <ul>
 *   <li>{@code awaitReady} 超时抛 {@link ServerUnreachableException}
 *       （"websocket handshake did not complete within %dms"）；{@code close()} 时也用这个类
 *       （"websocket client closed"）收掉还在等的 future。</li>
 *   <li>取连接地址那步（{@code getConnUrl}）里：HTTP 非 200 或没有响应体抛
 *       {@link ServerException}（code 是 HTTP 码，message 取自响应体）；
 *       业务码 {@code 1}（system busy）与 {@code 1000040343} 同样落到 {@link ServerException}。</li>
 *   <li>同一方法里其它任何非 0 业务码抛 {@link ClientException}(code, msg)，
 *       应用不存在、未开通长连接、参数不对这类服务端明确拒绝都在这里。</li>
 *   <li>本地凭据缺失也用它：{@code appSecret} 与 {@code clientAssertionProvider} 都空时 {@code 7104}，
 *       client assertion 为空时 {@code 7101}。</li>
 * </ul>
 *
 * <p>{@link ClientException} 因此判永久：它的两个来源（服务端拒绝握手、本地凭据缺失）换个时间点
 * 还是错。已知的不确定是限流——如果服务端哪天把限流也做成非 0 业务码，它同样落进这里，
 * 一次限流就让应用起不来。取舍是宁可错在启动，也不带着一个永远连不上的通道跑，
 * 所以没有归进默认可恢复，而是把它的 {@code toString()}（形如
 * {@code ClientException: 10014: app not found}）整段写进日志，让运维一眼看出是哪个码。
 * 真要按码细分，SDK 没有 {@code getCode()}，只能解析这个字符串，那时再改，
 * 不预先写一个会漂的解析器。
 *
 * <p>其余一律可恢复：{@link IOException}（DNS、连接被拒、握手断开）、{@link TimeoutException}，
 * 以及任何认不出来的运行时异常。默认可恢复是刻意的：判错一个永久错会让整站起不来，
 * 多一次重试最多晚三十秒。唯一例外是 {@link InterruptedException}，那不是连不上，是有人在让停。
 */
public final class FeishuConnectionFailures {

    public enum Kind {

        /** 换一个时间点重试还是错：凭据、应用状态、参数。 */
        PERMANENT,

        /** 等一个会变的事实：网络、对端、超时。 */
        RECOVERABLE
    }

    /**
     * 一条失败判定：类别加一句原因。
     *
     * @param kind 哪一类
     * @param reason 一句话，进日志也进启动失败的报错——运维看的是这句，不是堆栈
     */
    public record Verdict(Kind kind, String reason) {

        public boolean isPermanent() {
            return kind == Kind.PERMANENT;
        }
    }

    private FeishuConnectionFailures() {
    }

    /**
     * 判定一次建连失败要不要重试。
     *
     * <p>{@code null} 按可恢复处理：重试一次比整站起不来便宜。
     *
     * @param failure 建连抛出的异常，可以为 {@code null}
     * @return 判定结果，含类别与一句给运维看的原因
     */
    public static Verdict classify(Throwable failure) {
        if (failure == null) {
            return new Verdict(Kind.RECOVERABLE, "没有异常信息（按可恢复处理，重试一次比整站起不来便宜）");
        }
        if (failure instanceof InterruptedException) {
            return new Verdict(Kind.PERMANENT, "建连被中断，不再重试");
        }
        if (failure instanceof ClientException) {
            return new Verdict(Kind.PERMANENT,
                    "飞书拒绝了握手（凭据或应用状态：应用不存在、未开通长连接、密钥不对）");
        }
        if (failure instanceof ServerUnreachableException) {
            return new Verdict(Kind.RECOVERABLE, "握手没在超时内完成（网络或对端）");
        }
        if (failure instanceof ServerException) {
            return new Verdict(Kind.RECOVERABLE, "飞书这一侧暂时不可用（网关错或 system busy）");
        }
        if (failure instanceof IOException) {
            return new Verdict(Kind.RECOVERABLE, "网络层失败（DNS、连接被拒、链路断开）");
        }
        if (failure instanceof TimeoutException) {
            return new Verdict(Kind.RECOVERABLE, "超时");
        }
        return new Verdict(Kind.RECOVERABLE, "认不出来的失败，按可恢复处理");
    }
}
