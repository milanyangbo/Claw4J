package io.github.claw4j.rag.index;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 知识的正文摘要：同一份内容算出同一个值，用来判"这份文档变了没有"。
 *
 * <p>取 SHA-256 而不是别的：索引表的 {@code content_hash} 是定长十六进制列，换算法等于让所有
 * 已有行都变成"没算过"。写入侧算它，外部要判断"这份内容进过库没有"也算同一个值，所以对包外可见。
 */
public final class ContentHash {

    private ContentHash() {
    }

    /**
     * 正文的摘要。
     *
     * @param content 原文，按 UTF-8 取字节
     * @return 64 个十六进制字符
     */
    public static String of(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException impossible) {
            // JDK 必须提供 SHA-256，缺了就不是这个仓库能处理的问题。
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }
}
