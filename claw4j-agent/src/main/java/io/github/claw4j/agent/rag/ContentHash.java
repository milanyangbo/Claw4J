package io.github.claw4j.agent.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 知识的正文摘要：同一份内容算出同一个值，用来判"这份文档变了没有"。
 *
 * <p>它原来住在一个把身份压成短串的指纹类里，跟身份绑定在一起。鉴权那一层在
 * ADR-0030 整块下线之后，身份不再有指纹，而"内容变了没有"这件事与权限毫无关系——顺手删掉
 * 这个静态方法会让索引在第二次跑时把每份文档都当成新的。所以把它挪到这里，一个只做这一件事
 * 的类。
 *
 * <p>取 SHA-256 而不是别的：索引表的 {@code content_hash} 是定长十六进制列，换算法等于让所有
 * 已有行都变成"没算过"。
 */
final class ContentHash {

    private ContentHash() {
    }

    /**
     * 正文的摘要。
     *
     * @param content 原文，按 UTF-8 取字节
     * @return 64 个十六进制字符
     */
    static String of(String content) {
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
