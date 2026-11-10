package io.github.claw4j.agent;

import io.github.claw4j.domain.principal.Principal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;

/**
 * 把一份身份能看见的表与可执行的动作压成一个短字符串。
 *
 * <p>两处使用：语义缓存据此判断两次提问的权限是否同一回事（权限不同看到的数据不同，答案不能复用）；
 * 会话据此判断是不是同一个人在同一种权限下继续对话（权限变了，历史里的数字不能再当事实带进上下文）。
 *
 * <p>不取 {@code userId}：同角色的人就共用不了缓存。不取角色名：改一次角色配置会让所有缓存同时失效，
 * 而这种改动看上去与缓存无关。不取 {@link Principal#toString()}：随字段顺序漂移。
 *
 * <p>代价是权限一变，会话与缓存都从头开始，表现为这个人突然什么都想不起来了。另一条路是
 * 降权之后历史里的高权限数字仍能被问出来，这里选前者。
 */
public final class PermissionFingerprint {

    /** 拼进会话 id 的那一段取前 16 个十六进制字符（64 bit）。 */
    static final int SHORT_LENGTH = 16;

    private PermissionFingerprint() {
    }

    /**
     * 计算完整指纹，64 个十六进制字符。
     *
     * <p>{@code tables} 与 {@code actions} 各自排序后拼接再哈希：集合的迭代顺序不保证稳定，
     * 不排序则同一份权限能算出两个指纹，缓存永远不命中。空集合也得出值，且与有权限的指纹不同。
     *
     * @param who 身份，取它的 {@code accessibleTables()} 与 {@code allowedActions()}
     * @return 64 个十六进制字符的 SHA-256 摘要
     */
    public static String of(Principal who) {
        String tables = String.join(",", new TreeSet<>(who.accessibleTables()));
        String actions = String.join(",", new TreeSet<>(who.allowedActions()));
        return sha256(tables + "|" + actions);
    }

    /**
     * 计算短指纹，用于拼进会话 id 与 sql 的 where 条件。
     *
     * <p>64 bit 对「几个角色」这个量级够用：碰撞的后果是两种权限共用一段会话，
     * 而要撞上得先构造出两个哈希前缀相同的集合。
     *
     * @param who 身份
     * @return 完整指纹的前 16 个十六进制字符
     */
    public static String shortOf(Principal who) {
        return of(who).substring(0, SHORT_LENGTH);
    }

    /**
     * 计算 SHA-256 摘要并输出小写十六进制，{@code questionKey} 之类的键也走这里。
     *
     * @param value 待哈希的原文，按 UTF-8 取字节
     * @return 64 个十六进制字符
     */
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
