package io.github.claw4j.agent.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * 把消息里的身份换成假名（ADR-0037 票 04）。
 *
 * <p>形状照 OpenClaw：{@code hmac-sha256:v1:<keyId>:<digest>}
 * （{@code docs/gateway/protocol/ledgers.md:41-48}）。三段各有作用：
 *
 * <ul>
 *   <li>{@code hmac-sha256} 说明算法，将来换算法时旧行认得出；</li>
 *   <li>{@code keyId} 是密钥前 8 字节的 hex——换过密钥之后，一眼看出这一行是哪一把算的，
 *       否则旧假名会变成"对不上任何人的一串"；</li>
 *   <li>{@code digest} 是 {@code HMAC(secret, 原文)} 的前 128 位。</li>
 * </ul>
 *
 * <p>为什么用 HMAC 而不是 sha256：账号、会话地址这些值的取值空间很小（手机号、openid 有固定前缀），
 * 裸哈希拿字典就能反查。带密钥之后，只有握着密钥的那个人能把假名对回原文——而审计要回答的是
 * "是不是同一个人反复没收到"，那个问题不需要知道他是谁。
 *
 * <p>密钥落库（{@code claw4j_run.audit_identity_key}，单行）而不是读配置：多实例要算出同一个假名，
 * 而读配置的话换一次部署就换一套假名，历史行再也对不上同一个人。表里那一行只在第一次用的时候生成，
 * 读接口从不返回它。
 *
 * <p>取不到密钥时返回 null 而不是抛：审计缺一行不该让一条消息处理失败。代价是那一格空着，
 * 而"空着一格"比"整条消息失败"轻得多。
 */
public final class AuditPseudonyms {

    /** 假名前缀里的算法段。换算法时改这里，旧行的前缀仍然认得出。 */
    private static final String ALGORITHM = "HmacSHA256";

    private static final String PREFIX = "hmac-sha256:v1:";

    /** digest 截到 128 位：列宽 96，而 128 位足够区分，再长只是多占位置。 */
    private static final int DIGEST_HEX_CHARS = 32;

    private static final int KEY_BYTES = 32;

    private static final Logger log = LoggerFactory.getLogger(AuditPseudonyms.class);

    private final JdbcTemplate jdbc;

    private volatile String keyId;

    private volatile SecretKeySpec key;

    /**
     * @param jdbc 主连接池，必填
     */
    public AuditPseudonyms(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 原文的假名。
     *
     * @param raw 原文；null / 空白时返回 null（没有可假名化的东西）
     * @return {@code hmac-sha256:v1:<keyId>:<digest>}，或者取不到密钥时的 null
     */
    public String ref(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            ensureKey();
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            String digest = HexFormat.of()
                    .formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
            return PREFIX + keyId + ':' + digest.substring(0, DIGEST_HEX_CHARS);
        } catch (Exception unavailable) {
            log.debug("假名算不出来（密钥取不到），这一格留空", unavailable);
            return null;
        }
    }

    /**
     * 取密钥，没有就生成一行。
     *
     * <p>{@code on conflict do nothing} + 再读一次：两个实例同时首启时，只有一个能插进去，
     * 另一个读到的必须是已经落库的那一把——各自用自己刚生成的那把会让同一批消息分裂成两套假名。
     */
    private void ensureKey() {
        if (key != null) {
            return;
        }
        synchronized (this) {
            if (key != null) {
                return;
            }
            List<byte[]> secrets = jdbc.queryForList(
                    "select secret from claw4j_run.audit_identity_key where id = 1", byte[].class);
            if (secrets.isEmpty()) {
                byte[] fresh = new byte[KEY_BYTES];
                new SecureRandom().nextBytes(fresh);
                jdbc.update("insert into claw4j_run.audit_identity_key (id, secret)"
                        + " values (1, ?) on conflict (id) do nothing", (Object) fresh);
                secrets = jdbc.queryForList(
                        "select secret from claw4j_run.audit_identity_key where id = 1", byte[].class);
            }
            byte[] secret = secrets.getFirst();
            keyId = HexFormat.of().formatHex(secret, 0, 8);
            key = new SecretKeySpec(secret, ALGORITHM);
            log.info("消息审计的假名密钥就位（keyId={}）", keyId);
        }
    }
}
