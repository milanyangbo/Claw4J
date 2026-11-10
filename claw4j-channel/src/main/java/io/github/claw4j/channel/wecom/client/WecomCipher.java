package io.github.claw4j.channel.wecom.client;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

/**
 * 企业微信回调的两道关：<b>签名</b>（这条回调是不是知道 token 的人发的）与
 * <b>解密</b>（正文到底是什么）。
 *
 * <p>两件事都不难，但每一条细节抄错的表现都只是回调静默失败——没有异常，没有日志，
 * 用户那边就是没有回答。所以这里的每一步都写明它在防什么：
 *
 * <ol>
 *   <li>签名是 SHA-1，不是 HMAC。把 {@code token}、{@code timestamp}、{@code nonce}、
 *       {@code Encrypt} 四个字符串按字典序排好再首尾相接，取 SHA-1 十六进制小写。
 *       密钥不参与运算，所以它只证明发送方知道 token，不提供保密性。少排一次序，
 *       或者把 token 放到末尾，算出来的值永远对不上。</li>
 *   <li>密钥从 EncodingAESKey 派生：43 位字符串补一个 {@code =} 之后 Base64 解码，
 *       得到 32 字节 AES-256 密钥。IV 是这 32 字节的前 16 字节——固定 IV 在这里不是疏漏，
 *       协议就是这么定的。</li>
 *   <li>明文有固定布局：{@code 随机 16 字节 + 4 字节大端长度 + 正文 + receiveid}。
 *       剥掉 AES 的 PKCS#7 填充之后，最后那段 receiveid 必须等于本企业的 corpid。
 *       不校验它，别家企业发给这个地址的回调也能解出一段像模像样的消息，
 *       而那正好是「签名通过、解密成功、内容却是别人家的」这种最难想到的错。</li>
 * </ol>
 *
 * <p>失败一律抛 {@link IllegalStateException}，由调用方记 WARN 后回空串：签名错、密钥错、
 * receiveid 不对，都是重试也不会好的事，不该让平台重推。
 */
public class WecomCipher {

    /** EncodingAESKey 的长度。少一位、多一位都解不出来，所以在构造时就拦下。 */
    static final int ENCODING_AES_KEY_LENGTH = 43;

    private static final String ALGORITHM = "AES";

    /** 分组 16 字节，PKCS5 在 16 字节分组上就是 PKCS#7。 */
    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";

    private static final int KEY_LENGTH = 32;

    private static final int IV_LENGTH = 16;

    /** 随机数 16 字节 + 长度 4 字节：正文之前至少这么多。 */
    private static final int PREFIX_LENGTH = 20;

    private final String token;

    private final String corpId;

    private final byte[] key;

    private final IvParameterSpec iv;

    /**
     * 建一把签名与解密用的钥匙，构造时就把三样配置校验一遍。
     *
     * @param token 后台「接收消息」里那个 Token，可以随意取，只要两边一致
     * @param encodingAesKey 后台同一个页面上的 43 位 EncodingAESKey
     * @param corpId 本企业 ID，用来校验解密出来的 receiveid
     * @throws IllegalStateException 任一配置为空，或密钥长度、Base64 不合法
     */
    public WecomCipher(String token, String encodingAesKey, String corpId) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("claw4j.channel.wecom.token 是空的：签名无从算起。");
        }
        if (encodingAesKey == null || encodingAesKey.length() != ENCODING_AES_KEY_LENGTH) {
            throw new IllegalStateException(
                    "claw4j.channel.wecom.encoding-aes-key 必须是 43 位（后台那个 EncodingAESKey 原样复制），"
                            + "现在拿到的是 " + (encodingAesKey == null ? "null" : encodingAesKey.length() + " 位") + "。");
        }
        if (corpId == null || corpId.isBlank()) {
            throw new IllegalStateException("claw4j.channel.wecom.corp-id 是空的：解出来的 receiveid 没得比。");
        }
        this.token = token;
        this.corpId = corpId;
        this.key = decodeKey(encodingAesKey);
        this.iv = new IvParameterSpec(Arrays.copyOfRange(key, 0, IV_LENGTH));
    }

    /**
     * 校验签名。
     *
     * <p>比较用 {@link MessageDigest#isEqual}：字符串 {@code equals} 一遇到不同的字符就返回，
     * 逐字节的时间差能反推出前缀，这是签名比对的经典写法。
     *
     * @param signature 平台带来的 {@code msg_signature}
     * @param timestamp 平台带来的时间戳
     * @param nonce 平台带来的随机串
     * @param encrypted 密文，签名覆盖的是它
     * @return 四个值齐全且签名一致时为 {@code true}
     */
    public boolean verify(String signature, String timestamp, String nonce, String encrypted) {
        if (isBlank(signature) || isBlank(timestamp) || isBlank(nonce) || isBlank(encrypted)) {
            return false;
        }
        byte[] expected = signatureOf(timestamp, nonce, encrypted).getBytes(StandardCharsets.US_ASCII);
        byte[] provided = signature.strip().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, provided);
    }

    /**
     * 解一段 Base64 密文，返回里面的明文正文。
     *
     * <p>URL 验证的 {@code echostr} 走的是同一条路：它也是「随机数 + 长度 + 正文 + receiveid」，
     * 只不过正文就是要原样回给平台的字符串，所以这里只需要一个解密方法。
     *
     * @param encrypted Base64 编码的密文
     * @return 明文正文，不含随机数、长度与 receiveid
     * @throws IllegalStateException 密文坏了、密钥不对、receiveid 不是本企业
     */
    public String decrypt(String encrypted) {
        if (isBlank(encrypted)) {
            throw new IllegalStateException("密文是空的");
        }
        byte[] plain;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, ALGORITHM), iv);
            plain = cipher.doFinal(Base64.getDecoder().decode(encrypted));
        } catch (RuntimeException | java.security.GeneralSecurityException failure) {
            // 密钥与平台那边不一致时走到这里：BadPadding / IllegalBlockSize 都算
            throw new IllegalStateException("解不开回调密文（多半是 EncodingAESKey 与后台不一致）："
                    + failure.getClass().getSimpleName(), failure);
        }
        if (plain.length < PREFIX_LENGTH) {
            throw new IllegalStateException("解出来的明文只有 " + plain.length + " 字节，连长度字段都放不下");
        }
        int length = ByteBuffer.wrap(plain, IV_LENGTH, 4).getInt();
        if (length < 0 || PREFIX_LENGTH + length > plain.length) {
            throw new IllegalStateException("明文里的长度字段是 " + length + "，与解出来的 "
                    + plain.length + " 字节对不上");
        }
        String message = new String(plain, PREFIX_LENGTH, length, StandardCharsets.UTF_8);
        String receiveId = new String(plain, PREFIX_LENGTH + length, plain.length - PREFIX_LENGTH - length,
                StandardCharsets.UTF_8);
        if (!corpId.equals(receiveId)) {
            throw new IllegalStateException("解出来的 receiveid 是 " + receiveId
                    + "，不是本企业的 corpid（" + corpId + "）：这条回调不是发给这个应用的。");
        }
        return message;
    }

    /**
     * 四段排序后拼接再取 SHA-1。排序是协议的一部分，不是顺手排一下：
     * 平台那边就是按字典序拼的，这里少排一次，签名永远对不上。
     */
    private String signatureOf(String timestamp, String nonce, String encrypted) {
        String[] parts = {token, timestamp.strip(), nonce.strip(), encrypted.strip()};
        Arrays.sort(parts);
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(String.join("", parts).getBytes(StandardCharsets.UTF_8));
            return hex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-1 是 JDK 必须提供的算法，拿不到只可能是 JDK 坏了
            throw new IllegalStateException("JDK 没有 SHA-1", impossible);
        }
    }

    private static byte[] decodeKey(String encodingAesKey) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodingAesKey + "=");
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException(
                    "claw4j.channel.wecom.encoding-aes-key 不是合法的 Base64："
                            + "后台给的那 43 位里不该有空格或换行，检查是不是复制时多带了字符。", notBase64);
        }
        if (decoded.length != KEY_LENGTH) {
            throw new IllegalStateException(
                    "claw4j.channel.wecom.encoding-aes-key 解出来是 " + decoded.length
                            + " 字节，AES-256 要 32 字节——这串不是后台那个 EncodingAESKey。");
        }
        return decoded;
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(Character.forDigit((value >> 4) & 0xF, 16));
            text.append(Character.forDigit(value & 0xF, 16));
        }
        return text.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
