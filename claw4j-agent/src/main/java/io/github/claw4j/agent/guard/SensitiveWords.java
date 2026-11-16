package io.github.claw4j.agent.guard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 入站拦截词表的文件读取。
 *
 * <p>格式只有一个约定：一行一个词，行首 {@code #} 与空行跳过。选纯文本不选 YAML，是因为改这份
 * 文件的人多半是在处理一次突发：新出一种套话话术，就往里加一行。YAML 的缩进在这种时候只会
 * 帮倒忙——缩进错了不会报错，绑成空表以后整个拦截不装配，消息直通模型。
 *
 * <p>{@code #} 只认行首：词里带 {@code #} 的情况不存在，而"行尾注释"会让"忽略之前"与
 * "忽略之前的指令 # 太宽了"看起来是同一个词，这种模糊不值得省那几行注释。
 *
 * <p>文件不存在就是启动失败，不是空表。空表与"没装拦截"在行为上等价，肉眼看不出区别——
 * 这正是这次要消掉的那种假象（{@link GuardAutoConfiguration} 只在词表非空时装配）。
 */
public final class SensitiveWords {

    /** 词表文件里注释行的前导字符。 */
    private static final String COMMENT_PREFIX = "#";

    private SensitiveWords() {
    }

    /**
     * 读词表。
     *
     * @param file 词表文件路径，相对路径按启动时的工作目录解析
     * @return 词表，按文件里的顺序、已去重；文件里只有注释与空行时为空列表
     * @throws IllegalStateException 文件不存在、不是普通文件，或读不动
     */
    public static List<String> load(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new IllegalStateException(
                    "claw4j.guard.sensitive-words-file 指向的文件不存在或不是普通文件：" + absolute
                            + "。相对路径按启动时的工作目录解析，部署时用 CLAW4J_GUARD_WORDS_FILE 指到实际路径");
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(absolute, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new IllegalStateException(
                    "claw4j.guard.sensitive-words-file 指向的文件读不动：" + absolute, e);
        }

        Set<String> words = new LinkedHashSet<>();
        for (String line : lines) {
            String word = stripBom(line).trim();
            if (word.isEmpty() || word.startsWith(COMMENT_PREFIX)) {
                continue;
            }
            words.add(word);
        }
        return List.copyOf(words);
    }

    /**
     * 去掉 UTF-8 BOM。
     *
     * <p>Windows 记事本存下来的文件带 BOM，不去掉的话第一条词前面多一个不可见字符，
     * 这条词从此永远不会命中——而文件里看着一切正常。
     */
    private static String stripBom(String line) {
        return line.startsWith("\uFEFF") ? line.substring(1) : line;
    }
}
