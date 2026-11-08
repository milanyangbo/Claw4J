package io.github.claw4j.channel.feishu.sdk;

import com.lark.oapi.ws.exception.ClientException;
import com.lark.oapi.ws.exception.ServerException;
import com.lark.oapi.ws.exception.ServerUnreachableException;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * 把"连不上"分成两类：**换个时间点重试会不会好**。
 *
 * <p>OpenClaw 的分法（`docs/channels/slack/transports.md:71`）：可恢复的启动/等待/断开失败
 * "retry until the channel stops"，而凭据类永久错"fail fast instead of retrying forever"。
 * 我们原来把两类都判成永久——一次对端抖动就是一次全站起不来。
 *
 * <p><b>下面每一条都是按 lark-oapi 2.8.7 的字节码现场核过的</b>，不是按异常名字猜的
 * （{@code javap -p -c com.lark.oapi.ws.Client}）：
 *
 * <ul>
 *   <li>{@code awaitReady} 超时抛 {@link ServerUnreachableException}
 *       （"websocket handshake did not complete within %dms"）；{@code close()} 时它也会用这个类
 *       （"websocket client closed"）把还在等的 future 收掉。</li>
 *   <li>取连接地址那步（{@code getConnUrl}）里：HTTP 非 200 或没有响应体 →
 *       {@link ServerException}（code 是 HTTP 码，message 取自响应体）；
 *       业务码 {@code 1}（"system busy"）与 {@code 1000040343} → 也是 {@link ServerException}。</li>
 *   <li>同一个方法里**其它任何非 0 业务码** → {@link ClientException}(code, msg)。
 *       也就是说"应用不存在 / 未开通长连接 / 参数不对"这类**服务端明确拒绝**，落在这个类上。</li>
 *   <li>本地凭据缺失也用它：{@code appSecret} 与 {@code clientAssertionProvider} 都空 → {@code 7104}，
 *       client assertion 为空 → {@code 7101}。</li>
 * </ul>
 *
 * <p><b>所以 {@link ClientException} 判永久。</b>它的两个来源（服务端拒绝握手、本地凭据缺失）
 * 都是"换一个时间点还是错"。此处有一个已知的不确定：如果哪天服务端把**限流**也做成非 0 业务码，
 * 它同样会落进这里，于是一次限流会让应用起不来。当前的取舍是"宁可错杀在启动，不要带着一个
 * 永远连不上的通道跑"——所以没有把 {@link ClientException} 归进"默认可恢复"，
 * 而是把它的 {@code toString()}（形如 {@code ClientException: 10014: app not found}）整段写进日志，
 * 让运维一眼看出是哪一个码。真要按码细分，SDK 没有 {@code getCode()}，只能解析这个字符串——
 * 那时再改，不预先写一个会漂的解析器。
 *
 * <p>其余一律可恢复：SDK 抛出的 {@link IOException}（DNS、连接被拒、握手断开）、
 * {@link TimeoutException}、以及任何认不出来的运行时异常。**默认可恢复**是刻意的：
 * 一条判错的永久错会让整站起不来，而一次多余的重试最多是晚三十秒。
 * 唯一的例外是 {@link InterruptedException}——那不是"连不上"，是有人在让我们停。
 */
public final class FeishuConnectionFailures {

    public enum Kind {

        /** 换一个时间点重试还是错：凭据、应用状态、参数。 */
        PERMANENT,

        /** 等一个会变的事实：网络、对端、超时。 */
        RECOVERABLE
    }

    /**
     * @param kind   哪一类
     * @param reason 一句话，进日志也进启动失败的报错——运维看的是这句，不是堆栈
     */
    public record Verdict(Kind kind, String reason) {

        public boolean isPermanent() {
            return kind == Kind.PERMANENT;
        }
    }

    private FeishuConnectionFailures() {
    }

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
