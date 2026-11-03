package io.github.claw4j.persona;

import java.util.Objects;

/**
 * 一份口径：它说什么、出自哪。
 *
 * <p>{@code source} 不是装饰。没有它，"这个数按什么算"最后只能答"文档里写的"——
 * 而文档有好几份，改过几轮之后没人说得清现在生效的是哪一份。
 * 拼进提示词时它会作为小标题出现，于是模型自己也能指出依据，
 * 出问题时能顺着这一行回到源头。
 *
 * @param source 出处：文件路径（相对人设目录）或人设里写的绝对路径
 * @param text   口径正文
 */
public record GlossarySection(String source, String text) {

    public GlossarySection {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(text, "text");
        if (source.isBlank()) {
            throw new IllegalArgumentException("口径必须能指出出处，source 不能为空。");
        }
    }
}
