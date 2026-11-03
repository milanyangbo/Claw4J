package io.github.claw4j.channel.dingtalk.client;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;

/**
 * 钉钉机器人回调的验签。<b>这是这个通道唯一的密码学动作，也是唯一的门。</b>
 *
 * <p>算法（官方文档 {@code developers.dingtalk.com/document/robots/receive-message} 的示例代码）：
 *
 * <pre>
 *   sign = Base64( HmacSHA256( key = appSecret, data = timestamp + "\n" + appSecret ) )
 * </pre>
 *
 * <p>注意 key 与 data 里都有 appSecret：它不是"用密钥签一段报文"的常规形状，
 * 照常规写成 {@code Hmac(secret, timestamp)} 会算出一串永远对不上的值——
 * 而症状是"所有回调都被拒"，看起来像配置错。
 *
 * <p>与企业微信那套的三点不同，都写在这里，因为改这个类的人一定会问：
 *
 * <ol>
 *   <li><b>签名在 header 里</b>（{@code sign} 与 {@code timestamp}），不在 query 里。</li>
 *   <li><b>算法是 HMAC-SHA256</b>，不是"排序后拼起来取 SHA-1"。</li>
 *   <li><b>报文本身不加密</b>，所以签名覆盖的不是密文，只是那个时间戳。
 *       这意味着<b>报文内容没有被签名保护</b>——能改报文的人也能用同一个 timestamp 造一条
 *       合法签名吗？不能（他没有 appSecret），但在 1 小时的窗口内，一条被截获的
 *       {@code (timestamp, sign)} 对<b>可以</b>配上任意报文重放。窗口就是这条防线：
 *       文档规定"与当前时间相差 1 小时以上即非法"，所以重放的有效期是 1 小时。
 *       这一点不许含糊，因为它是"为什么必须校验时间戳"的全部理由——
 *       只验签名不验时间戳，等于让签名永不过期。</li>
 * </ol>
 *
 * <p>时间戳单位：文档写的是<b>毫秒</b>。但这里按<b>位数</b>认（13 位当毫秒、10 位当秒）：
 * 判错的后果是"全部回调被拒"，而位数没有歧义，两种都认的成本是两行代码。
 * 反过来只认毫秒的话，万一对面某天改成秒，症状同样是全拒——那时没人会想到是单位问题。
 */
public final class DingtalkSignature {

    /** 文档规定的窗口：与当前时间相差超过它就是非法。 */
    static final Duration MAX_SKEW = Duration.ofHours(1);

    private static final String ALGORITHM = "HmacSHA256";

    /** 13 位的整数按毫秒解释，10 位的按秒解释。见类注释。 */
    private static final int MILLIS_DIGITS = 13;

    private static final int SECONDS_DIGITS = 10;

    private final byte[] secret;

    public DingtalkSignature(String appSecret) {
        if (appSecret == null || appSecret.isBlank()) {
            // 空密钥也能算出签名（Hmac 允许空 key），于是它会"验得过"任意自造的请求。
            // 这不是配置不全，这是把门开着——所以构造就失败，而不是等到第一条回调。
            throw new IllegalStateException(
                    "钉钉通道要验签，但 claw4j.channel.dingtalk.client-secret 是空的："
                            + "没有它算不出签名，也就没有任何请求能被信任。");
        }
        this.secret = appSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @param timestamp header 里的 {@code timestamp}，毫秒或秒（见类注释）
     * @param sign      header 里的 {@code sign}
     * @return {@code true} 表示签名对得上<b>且</b>时间戳在窗口内
     */
    public boolean verify(String timestamp, String sign) {
        if (isBlank(timestamp) || isBlank(sign)) {
            return false;
        }
        if (!withinWindow(timestamp.strip())) {
            return false;
        }
        byte[] expected = signOf(timestamp.strip()).getBytes(StandardCharsets.UTF_8);
        // 定长比较：按位短路会让"前几位对不对"从耗时里漏出去。
        // 这里漏的是一位 Base64 字符（约 6 bit），实际利用价值极低，但没有理由不用反正更省事的写法。
        return MessageDigest.isEqual(expected, sign.strip().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 算一遍签名。包级可见是为了让用例能<b>另写一份</b>实现来互相校验——
     * 用生产的 {@code signOf} 去验生产的 {@code verify}，两边一起错也照样绿。
     */
    String signOf(String timestamp) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] digest = mac.doFinal((timestamp + "\n" + new String(secret, StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException impossible) {
            // HmacSHA256 是 JDK 必备算法；走到这里说明运行环境坏了，不是配置问题
            throw new IllegalStateException("算不出钉钉签名：这个 JVM 不支持 " + ALGORITHM, impossible);
        }
    }

    /**
     * 时间戳是否在窗口内。<b>解析不出来也判非法</b>：一个看不懂的时间戳不能当作"大概是对的"。
     */
    private static boolean withinWindow(String timestamp) {
        long value;
        try {
            value = Long.parseLong(timestamp);
        } catch (NumberFormatException notANumber) {
            return false;
        }
        // 10 位及以下按秒；其余按毫秒。11–12 位那个区间既不可能是合法的秒值、
        // 也不可能是合法的毫秒值，按毫秒算出来的差值会被下面的窗口判定挡掉——不需要单独分支。
        long thenMillis = timestamp.length() <= SECONDS_DIGITS ? value * 1000L : value;
        return Math.abs(System.currentTimeMillis() - thenMillis) <= MAX_SKEW.toMillis();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
