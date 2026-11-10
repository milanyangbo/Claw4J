package io.github.claw4j.persona;

import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.markdown.FrontMatter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 一个人设：这个部署是谁、怎么说话、从哪份口径来、允许多宽的工具。
 *
 * <p>它解决的是"同一份代码两次部署只改配置"里最硬的那一半：系统提示词若留在
 * {@code AgentAutoConfiguration} 里当一个 Java 常量，改一个字就要重新编译发版，而"销售助手"
 * 与"运维助手"的差别首先就长在那三行里。
 *
 * <p>形状与子 Agent 的定义同源（{@code SubagentDefinition}）：一份 markdown，front matter 是
 * 配置、正文是它做事时用的系统提示词。这不是为了整齐——两套形状意味着两套解析与两套失败姿势，
 * 而同一份写错的 front matter 在一处报错、在另一处静默的后果，在这个项目里每次都一样：配置
 * 看着生效了，实际没生效。
 *
 * <p>只覆盖档位，不覆盖 allow / deny：{@code tier} 回答"这个 agent 允许多宽"（销售助手永远
 * 不该跑脚本，哪怕部署的全局档位是 full），而 allow / deny 回答"这套部署额外排除什么"——
 * 后者是部署级的事实，跟着 agent 走会让"同一个 agent 换个部署就少了几个工具"，那正是 per-agent
 * 配置最容易变得说不清的地方。
 *
 * <p>口径是引用，不是内容：{@code glossary} 里写的是文件路径，不是把口径抄进每一份人设。
 * 抄进去的后果可预见：两份 agent 的口径各写一遍，改一处必漏一处，而漏掉的那处会让两个助手对
 * 同一个数字给出两种说法。
 *
 * @param name         配置里点名它用的名字（{@code claw4j.agent.default}）
 * @param description  一句话说明它是干什么的。选不到 agent 时要靠它列候选，所以不能空
 * @param systemPrompt 正文。它做事的方式全写在这里
 * @param tier         覆盖全局 {@code claw4j.tools.profile}；空表示用全局那份
 * @param glossary     口径文件的引用（相对人设目录或绝对路径）；空表示这个 agent 没有额外口径
 */
public record Persona(String name,
                      String description,
                      String systemPrompt,
                      Optional<ToolTier> tier,
                      List<String> glossary) {

    /** front matter 里允许逐行写列表项的键；其余键只认同行值。 */
    private static final Set<String> LIST_KEYS = Set.of("glossary");

    public Persona {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(glossary, "glossary");

        if (name.isBlank()) {
            throw new IllegalStateException("人设的 name 不能为空：配置里就是靠它点名。");
        }
        if (description.isBlank()) {
            throw new IllegalStateException(
                    "人设 " + name + " 的 description 不能为空：选不到 agent 时要靠这一行列出候选。");
        }
        if (systemPrompt.isBlank()) {
            throw new IllegalStateException(
                    "人设 " + name + " 的正文（系统提示词）不能为空：它是什么样的人全写在这里。"
                            + "正文留空的后果不是“没有风格”，而是模型拿到一段空白指令后按自己的默认脾气来。");
        }
        glossary = List.copyOf(glossary);
    }

    /**
     * 从一个 markdown 文件读出人设。文件读不出来、front matter 缺字段都是配置错误，一律失败。
     *
     * @param file 人设文件
     * @return 解析出的人设
     * @throws IllegalStateException 文件读不出来、缺 name 或 description、tier 写错
     */
    public static Persona parse(Path file) {
        Objects.requireNonNull(file, "file");
        String markdown;
        try {
            markdown = Files.readString(file);
        }
        catch (IOException failure) {
            throw new IllegalStateException("读不了人设文件：" + file, failure);
        }
        FrontMatter frontMatter = FrontMatter.parse(markdown, file, LIST_KEYS);
        String name = text(frontMatter, "name", file);
        return new Persona(
                name,
                text(frontMatter, "description", file),
                frontMatter.body(),
                tier(frontMatter, file),
                frontMatter.items("glossary"));
    }

    /**
     * {@code tier} 这一节：没写就是"用全局那份"，写了必须能认出来。
     *
     * <p>写错一个字母（{@code messging}）在这里失败而不是落回默认值——
     * 落回默认的表现是"配了 messaging 档却照样能跑脚本"，而日志里那一行看起来是对的。
     */
    private static Optional<ToolTier> tier(FrontMatter frontMatter, Path file) {
        if (!frontMatter.has("tier")) {
            return Optional.empty();
        }
        String declared = frontMatter.value("tier");
        if (declared == null || declared.isBlank()) {
            throw new IllegalStateException("人设文件 " + file + " 写了 tier 却没给值。"
                    + "要么删掉这一节（用全局的 claw4j.tools.profile），要么写出档位之一："
                    + candidates());
        }
        try {
            return Optional.of(ToolTier.valueOf(declared.trim().toUpperCase(java.util.Locale.ROOT)));
        }
        catch (IllegalArgumentException unknown) {
            throw new IllegalStateException("人设文件 " + file + " 的 tier 认不出来：" + declared
                    + "。可选的档位是 " + candidates() + "（不分大小写）。", unknown);
        }
    }

    private static String candidates() {
        return String.join("、", java.util.Arrays.stream(ToolTier.values())
                .map(tier -> tier.name().toLowerCase(java.util.Locale.ROOT))
                .toList());
    }

    private static String text(FrontMatter frontMatter, String key, Path file) {
        String value = frontMatter.value(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("人设文件 " + file + " 缺少 " + key
                    + "：front matter 里必须有 name 与 description。");
        }
        return value.trim();
    }

    /**
     * 给启动日志与"有哪些 agent"的提示用的一行。
     *
     * @return {@code - 名字: 说明}
     */
    public String registration() {
        return "- " + name + ": " + description;
    }
}
