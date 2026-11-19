package io.github.claw4j.agent.memory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 记忆目录怎么按人分。读写记忆与整理记忆都要落到这同一个路径上，所以只有这一份实现。
 *
 * <p>两件事都不能省：<b>安全化</b>——{@code userId} 来自通道（飞书 open_id、邮箱、工号都可能），
 * 里面有 {@code /} 或 {@code ..} 时 {@link Path#resolve} 会把根目录整个换掉，于是"这个人的记忆"
 * 落到别处；<b>哈希后缀</b>——安全化是多对一的（{@code a/b} 与 {@code a:b} 都变成 {@code a_b}），
 * 两个人撞进同一个目录就是互相读对方的记忆。
 */
final class MemoryDirectories {

    /** 安全化之后的 userId 留多长；太长的 id 会把路径撑过文件系统的单段上限。 */
    private static final int MAX_SLUG = 48;

    private MemoryDirectories() {
    }

    /**
     * 这个人在这套记忆根目录下的那一层。
     *
     * @param root 记忆根目录
     * @param userId 通道侧的用户标识
     * @return 根目录下的一个子目录（不保证已存在，第一次写入时由库创建）
     */
    static Path under(Path root, String userId) {
        return root.resolve(name(userId));
    }

    /**
     * 目录名：可读前缀 + SHA-256 前 12 位（48 bit）。
     *
     * <p>撞一条要试十亿量级的两两组合，而这里的全集是"这套部署里的用户数"。留成可读前缀是为了
     * 运维能一眼看出这是谁的目录——纯哈希的目录名在磁盘上等于匿名。
     *
     * @param userId 原始标识
     * @return 形如 {@code user_1-a1b2c3d4e5f6}
     */
    static String name(String userId) {
        Objects.requireNonNull(userId, "userId");
        String slug = userId.replaceAll("[^A-Za-z0-9._-]", "_");
        if (slug.length() > MAX_SLUG) {
            slug = slug.substring(0, MAX_SLUG);
        }
        if (slug.isEmpty() || slug.equals(".") || slug.equals("..")) {
            slug = "user";
        }
        return slug + "-" + digest(userId);
    }

    private static String digest(String userId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(userId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        }
        catch (NoSuchAlgorithmException impossible) {
            // SHA-256 是 JDK 必须提供的算法；走到这里说明运行时不完整，不要降级成弱哈希。
            throw new IllegalStateException("这个 JVM 没有 SHA-256", impossible);
        }
    }
}
