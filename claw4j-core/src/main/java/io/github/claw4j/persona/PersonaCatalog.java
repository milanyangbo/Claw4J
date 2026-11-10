package io.github.claw4j.persona;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 一个目录里的人设：全都有哪些、这次用哪一个、它的口径是什么。
 *
 * <p>目录的形状很简单：顶层每个 {@code *.md} 是一份人设，子目录是口径之类的东西（顶层不递归，
 * 免得把口径文件也读成一个人设）。
 *
 * <p>选不到就失败，绝不挑一个"看起来像"的顶上。三种情况都是启动失败，且都列出候选：点名的那个
 * 人设不存在、目录里有多份却没写 {@code claw4j.agent.default}、目录是空的。一个进程只有一个
 * Agent 生效，所以"用了哪个人设"没有含糊空间——猜错的表现是"这个部署怎么说话像销售"，而要查
 * 很久才会想到去看默认值。
 *
 * <p>唯一允许不点名的情况是目录里只有一份：那时选择是唯一的，逼人再写一行默认值只是多一个会
 * 写错的地方。反过来，多份而不点名就失败。
 *
 * <p>口径引用可以指向目录外（{@code ../common/glossary.md} 或者绝对路径）：几个 agent 共用
 * 一份口径是常态，强行圈在目录里只会让人把同一段话抄两遍，而这里要解决的正是"抄两遍"。
 */
public final class PersonaCatalog {

    private PersonaCatalog() {
    }

    /**
     * 读出来的目录内容。
     *
     * @param personas 全部人设，按文件名顺序
     * @param selected 这次生效的那一个
     * @param glossary 选中那个人设的口径，按它声明的顺序
     */
    public record Catalog(Map<String, Persona> personas, Persona selected, List<GlossarySection> glossary) {

        public Catalog {
            Objects.requireNonNull(personas, "personas");
            Objects.requireNonNull(selected, "selected");
            Objects.requireNonNull(glossary, "glossary");
            // 用 LinkedHashMap 包一层而不是 Map.copyOf：后者不保证顺序，
            // 而"有哪几个人设"要按文件名顺序读——乱序的候选列表在排查时等于没有。
            personas = Collections.unmodifiableMap(new LinkedHashMap<>(personas));
            glossary = List.copyOf(glossary);
        }

        /** 有哪几个人设，一行一个。选不到时用它列候选，启动日志也打它。 */
        public String available() {
            return String.join("\n", personas.values().stream().map(Persona::registration).toList());
        }
    }

    /**
     * 读一个目录并选出这次生效的人设。
     *
     * @param directory   人设目录（{@code claw4j.agent.directory}）
     * @param defaultName 要用的那个人设（{@code claw4j.agent.default}）；空表示"目录里只有一份"
     * @return 全部人设、这次选中的那一个、以及它的口径
     * @throws IllegalStateException 目录不存在、没有 markdown、重名、点名的那个不存在、
     *                               多份却没点名、口径文件读不到
     */
    public static Catalog load(Path directory, String defaultName) {
        Objects.requireNonNull(directory, "directory");
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("配置了 claw4j.agent.directory 但它不存在或不是目录：" + root
                    + "。配了就应当有它——这是配置错误，不是“这一层不存在”。");
        }

        Map<String, Persona> personas = new LinkedHashMap<>();
        for (Path file : markdownFiles(root)) {
            Persona persona = Persona.parse(file);
            Persona previous = personas.putIfAbsent(persona.name(), persona);
            if (previous != null) {
                throw new IllegalStateException("人设目录里有两份都叫 " + persona.name() + " 的定义："
                        + file + " 与之前读过的那一份。" + "配置里点名 " + persona.name()
                        + " 时，谁生效取决于读文件的顺序——这种问题不该靠顺序回答。");
            }
        }
        if (personas.isEmpty()) {
            throw new IllegalStateException("人设目录里一个 .md 都没有：" + root
                    + "。空目录与“没配”不是一回事：配了却空着，说明人设没被部署到这个位置。");
        }

        Catalog partial = new Catalog(personas, select(personas, defaultName, root), List.of());
        return new Catalog(personas, partial.selected(), glossaryOf(root, partial.selected()));
    }

    /**
     * 不读目录、直接拿一份现成的人设（装配期的默认值走这条路）。
     *
     * <p>它存在是为了让"没配目录"这条路径与"配了目录"走同一套拼装逻辑：两条路各写一份提示词
     * 拼装，早晚会有一条漏掉时间上下文。
     *
     * @param persona  现成的人设
     * @param glossary 它的口径，按声明顺序
     * @return 只含这一份人设的目录内容
     */
    public static Catalog single(Persona persona, List<GlossarySection> glossary) {
        Objects.requireNonNull(persona, "persona");
        return new Catalog(Map.of(persona.name(), persona), persona, glossary);
    }

    private static List<Path> markdownFiles(Path root) {
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
        }
        catch (IOException failure) {
            throw new IllegalStateException("读不了人设目录：" + root, failure);
        }
    }

    private static Persona select(Map<String, Persona> personas, String defaultName, Path root) {
        String wanted = defaultName == null ? "" : defaultName.trim();
        if (!wanted.isEmpty()) {
            Persona selected = personas.get(wanted);
            if (selected == null) {
                throw new IllegalStateException("claw4j.agent.default 点名的人设 " + wanted
                        + " 不在 " + root + " 里。可用的是：\n" + available(personas));
            }
            return selected;
        }
        if (personas.size() == 1) {
            return personas.values().iterator().next();
        }
        throw new IllegalStateException("人设目录里有 " + personas.size() + " 份定义，但没有写 "
                + "claw4j.agent.default。这个进程只会用一个 agent，所以必须点名：\n" + available(personas));
    }

    private static String available(Map<String, Persona> personas) {
        return String.join("\n", personas.values().stream().map(Persona::registration).toList());
    }

    private static List<GlossarySection> glossaryOf(Path root, Persona persona) {
        List<GlossarySection> sections = new ArrayList<>();
        for (String reference : persona.glossary()) {
            Path file = root.resolve(reference).normalize();
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException("人设 " + persona.name() + " 引用的口径读不到：" + file
                        + "。口径是引用而不是抄写——引用断了就等于这个 agent 没有口径，"
                        + "而它会照旧回答，只是答案里的数字没有依据。");
            }
            try {
                sections.add(new GlossarySection(reference, Files.readString(file).trim()));
            }
            catch (IOException failure) {
                throw new IllegalStateException("读不了口径文件：" + file, failure);
            }
        }
        return List.copyOf(sections);
    }
}
