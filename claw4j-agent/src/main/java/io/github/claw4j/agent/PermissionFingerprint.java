package io.github.claw4j.agent;

import io.github.claw4j.domain.principal.Principal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;

/**
 * 把"这份身份能看见什么"压成一个短字符串。
 *
 * <p><b>它解决的是同一类问题的两个面</b>：语义缓存要用它判断"这两次提问的权限是不是一回事"
 * （不同权限看到的数据不同，答案不能互相复用）；会话要用它判断"这是不是同一个人在同一种权限下的
 * 连续对话"（权限变了，历史里那些数字就不该继续被当作事实带进上下文）。
 *
 * <p><b>三个"不取"各有代价</b>：取 {@code userId} 则同角色的人共用不了缓存（形同虚设）；
 * 取角色名则改一次配置、所有缓存同时失效（而且改配置看起来跟缓存无关）；
 * 取 {@link Principal#toString()} 会随字段顺序变化而漂移。
 *
 * <p><b>反过来说，它的代价也要记账</b>：权限一变，会话就是新的、缓存也不命中。
 * 表现是"这个人突然什么都想不起来了"——这是刻意的，因为另一条路是
 * "他被降权之后，历史里那些高权限数字还在，问一句照样能念出来"。
 */
public final class PermissionFingerprint {

    /** 拼进会话 id 的那一段取前 16 个十六进制字符（64 bit）。 */
    static final int SHORT_LENGTH = 16;

    private PermissionFingerprint() {
    }

    /**
     * 完整指纹（64 个十六进制字符）。
     *
     * <p>{@code tables} 与 {@code actions} 各自排序后拼再哈希：集合的迭代顺序不保证稳定，
     * 不排序的话同一份权限能算出两个指纹，缓存永远不命中。空集合（一个权限都没有）也算得出值，
     * 而且与"有权限"的指纹不同——这正是想要的。
     */
    public static String of(Principal who) {
        String tables = String.join(",", new TreeSet<>(who.accessibleTables()));
        String actions = String.join(",", new TreeSet<>(who.allowedActions()));
        return sha256(tables + "|" + actions);
    }

    /**
     * 短指纹，用于拼进会话 id 与 sql 的 where 条件。
     *
     * <p>64 bit 对"几个角色"这个量级足够：碰撞的唯一后果是两种权限共用一段会话，
     * 而真要撞上得先构造出两个哈希前缀相同的集合，比直接改代码难。
     */
    public static String shortOf(Principal who) {
        return of(who).substring(0, SHORT_LENGTH);
    }

    /** 仓库里唯一的 SHA-256 实现；{@code questionKey} 之类也走这里。 */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException impossible) {
            // JDK 必须提供 SHA-256，缺了就不是这个仓库能处理的问题。
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }
}
