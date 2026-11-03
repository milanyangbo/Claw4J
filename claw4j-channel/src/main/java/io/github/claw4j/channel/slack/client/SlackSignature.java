package io.github.claw4j.channel.slack.client;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Slack 的请求签名校验。<b>这是这个通道唯一的密码学动作，也是唯一的门。</b>
 *
 * <p>算法（官方文档 {@code docs.slack.dev/authentication/verifying-requests-from-slack}）：
 *
 * <pre>
 *   basestring = "v0:" + timestamp + ":" + rawBody
 *   signature  = "v0=" + hex( HmacSHA256( key = signingSecret, data = basestring ) )
 * </pre>
 *
 * <p>与钉钉那套的三点不同，都写在这里，因为改这个类的人一定会问：
 *
 * <ol>
 *   <li><b>签名覆盖整个报文</b>，不只是时间戳。钉钉那边签名只护了 {@code timestamp + secret}，
 *       报文被改过也验得过（窗口内可以重放）；这里报文本身进了 HMAC，改一个字就验不过。
 *       代价是<b>必须拿到原始字符串</b>：JSON 反序列化再序列化一遍，字段顺序与转义都会变，
 *       结果是"签名永远对不上"，而症状看起来像密钥配错。所以控制器收的是 {@code String body}。</li>
 *   <li><b>时间戳是秒</b>（10 位），窗口 <b>5 分钟</b>。钉钉是毫秒、窗口 1 小时。
 *       照抄钉钉的除法会得到 1970 年，于是所有回调都被判过期——一个不报错的全拒。</li>
 *   <li><b>前缀有两个</b>：basestring 里是 {@code v0:}，签名里是 {@code v0=}。
 *       少一个冒号或多一个等号，算出来都是一串永远对不上的十六进制。</li>
 * </ol>
 *
 * <p>窗口是这条防线的全部：签名本身不会过期，被截获的 {@code (timestamp, signature)} 对
 * 配上任意报文只能在 5 分钟内重放，超过就作废。只验签名不验时间戳，等于让签名永不过期。
 */
public final class SlackSignature {

    /** basestring 的版本前缀（冒号分隔）。 */
    static final String VERSION = "v0";

    /** 文档规定的窗口：与本地时间相差超过它就是重放。 */
    static final Duration MAX_SKEW = Duration.ofMinutes(5);

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public SlackSignature(String signingSecret) {
        if (signingSecret == null || signingSecret.isBlank()) {
            // 空密钥也能算出签名（Hmac 允许空 key），于是任何自造的请求都能"验得过"。
            // 这不是配置不全，这是把门开着——所以构造就失败，而不是等到第一条回调。
            throw new IllegalStateException(
                    "Slack 通道要验签，但 claw4j.channel.slack.signing-secret 是空的："
                            + "没有它算不出签名，也就没有任何请求能被信任。");
        }
        this.secret = signingSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @param timestamp header 里的 {@code X-Slack-Request-Timestamp}（秒）
     * @param signature header 里的 {@code X-Slack-Signature}（{@code v0=...}）
     * @param body      <b>原始</b>请求体，未反序列化的字符串
     * @return {@code true} 表示签名对得上<b>且</b>时间戳在 5 分钟窗口内
     */
    public boolean verify(String timestamp, String signature, String body) {
        if (isBlank(timestamp) || isBlank(signature) || body == null) {
            return false;
        }
        String stamp = timestamp.strip();
        if (!withinWindow(stamp)) {
            return false;
        }
        byte[] expected = signatureOf(stamp, body).getBytes(StandardCharsets.UTF_8);
        // 定长比较：按位短路会让"前几位对不对"从耗时里漏出去。漏的是一位十六进制（4 bit），
        // 实际利用价值极低，但没有理由不用反正更省事的写法。
        return MessageDigest.isEqual(expected, signature.strip().getBytes(StandardCharsets.UTF_8));
    }

    /** @return {@code v0=} 加小写十六进制——签名头就是这个形状，多一个字符都对不上 */
    private String signatureOf(String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] digest = mac.doFinal((VERSION + ":" + timestamp + ":" + body)
                    .getBytes(StandardCharsets.UTF_8));
            return VERSION + "=" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException impossible) {
            // HmacSHA256 是 JDK 必备算法；走到这里说明运行环境坏了，不是配置问题
            throw new IllegalStateException("算不出 Slack 签名：这个 JVM 不支持 " + ALGORITHM, impossible);
        }
    }

    /**
     * 时间戳是否在窗口内。<b>解析不出来也判非法</b>：一个看不懂的时间戳不能当作"大概是对的"。
     */
    private static boolean withinWindow(String timestamp) {
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException notANumber) {
            return false;
        }
        return Math.abs(System.currentTimeMillis() - seconds * 1000L) <= MAX_SKEW.toMillis();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
