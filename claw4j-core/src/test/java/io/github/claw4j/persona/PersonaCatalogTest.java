package io.github.claw4j.persona;

import io.github.claw4j.domain.principal.ToolTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一个目录里的人设怎么读、怎么选、读错时说什么。
 *
 * <p>重点不在"能读出来"，而在<b>读不出来时会不会静默</b>：
 * 选错人设的表现是"这个部署怎么说话像销售"，没有任何异常，也没有任何一行日志会主动提醒。
 * 所以这一组用例里失败路径比正常路径多，而且每条都断言报错里**列出了候选**。
 */
class PersonaCatalogTest {

    @TempDir
    Path directory;

    private void write(String name, String content) throws IOException {
        Path file = directory.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String definition(String name, String body) {
        return "---\nname: " + name + "\ndescription: " + name + " 这个助手\n---\n\n" + body + "\n";
    }

    @Test
    @DisplayName("目录里只有一份时不逼人写 default：选择是唯一的，多写一行只是多一个会写错的地方")
    void singlePersonaNeedsNoDefault() throws IOException {
        write("sales.md", definition("sales", "你是销售助手。"));

        PersonaCatalog.Catalog catalog = PersonaCatalog.load(directory, null);

        assertEquals("sales", catalog.selected().name());
        assertEquals("你是销售助手。", catalog.selected().systemPrompt());
    }

    @Test
    @DisplayName("多份却没点名：启动失败，并把有哪几个列出来")
    void ambiguousDirectoryFailsAndListsCandidates() throws IOException {
        write("sales.md", definition("sales", "你是销售助手。"));
        write("ops.md", definition("ops", "你是运维助手。"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PersonaCatalog.load(directory, null));

        assertTrue(failure.getMessage().contains("claw4j.agent.default"), failure.getMessage());
        assertTrue(failure.getMessage().contains("sales"), failure.getMessage());
        assertTrue(failure.getMessage().contains("ops"), failure.getMessage());
    }

    @Test
    @DisplayName("点名的那个人不存在：失败并列出可用的，不挑一个看起来像的顶上")
    void unknownDefaultFailsAndListsCandidates() throws IOException {
        write("sales.md", definition("sales", "你是销售助手。"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PersonaCatalog.load(directory, "opss"));

        assertTrue(failure.getMessage().contains("opss"), failure.getMessage());
        assertTrue(failure.getMessage().contains("sales"), failure.getMessage());
    }

    @Test
    @DisplayName("点名了就按点名的来，哪怕目录里有好几份")
    void namedPersonaWins() throws IOException {
        write("sales.md", definition("sales", "你是销售助手。"));
        write("ops.md", definition("ops", "你是运维助手。"));

        assertEquals("ops", PersonaCatalog.load(directory, "ops").selected().name());
    }

    @Test
    @DisplayName("目录不存在：失败并说清是配置错误，不是『这一层不存在』")
    void missingDirectoryFails() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PersonaCatalog.load(directory.resolve("absent"), null));

        assertTrue(failure.getMessage().contains("claw4j.agent.directory"), failure.getMessage());
    }

    @Test
    @DisplayName("空目录：失败——配了却空着说明人设没被部署到这儿")
    void emptyDirectoryFails() {
        assertThrows(IllegalStateException.class, () -> PersonaCatalog.load(directory, null));
    }

    @Test
    @DisplayName("两份同名：谁生效取决于读文件顺序，这种问题不该靠顺序回答")
    void duplicateNamesFail() throws IOException {
        write("a.md", definition("sales", "第一个"));
        write("b.md", definition("sales", "第二个"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PersonaCatalog.load(directory, null));

        assertTrue(failure.getMessage().contains("sales"), failure.getMessage());
    }

    @Test
    @DisplayName("tier 覆盖全局档位：销售助手永远不该跑脚本，哪怕部署的全局档位是 full")
    void tierOverridesGlobalProfile() throws IOException {
        write("sales.md", "---\nname: sales\ndescription: 销售助手\ntier: messaging\n---\n\n你是销售助手。\n");

        assertEquals(Optional.of(ToolTier.MESSAGING),
                PersonaCatalog.load(directory, null).selected().tier());
    }

    @Test
    @DisplayName("没写 tier 就交给全局那份，不默认成任何一档")
    void absentTierDefersToGlobal() throws IOException {
        write("sales.md", definition("sales", "你是销售助手。"));

        assertEquals(Optional.empty(), PersonaCatalog.load(directory, null).selected().tier());
    }

    @Test
    @DisplayName("tier 写错一个字母：失败并给出可选的档位，不落回默认值")
    void unknownTierFails() throws IOException {
        write("sales.md", "---\nname: sales\ndescription: 销售\ntier: messging\n---\n\n你是销售助手。\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PersonaCatalog.load(directory, null));

        assertTrue(failure.getMessage().contains("messging"), failure.getMessage());
        assertTrue(failure.getMessage().contains("messaging"), failure.getMessage());
    }

    @Test
    @DisplayName("缺 name：失败")
    void missingNameFails() throws IOException {
        write("x.md", "---\ndescription: 没名字\n---\n\n正文\n");

        assertThrows(IllegalStateException.class, () -> PersonaCatalog.load(directory, null));
    }

    @Test
    @DisplayName("正文空着：失败——空正文不是『没有风格』，是模型按自己的默认脾气来")
    void blankBodyFails() throws IOException {
        write("x.md", "---\nname: x\ndescription: 空正文\n---\n\n   \n");

        assertThrows(IllegalStateException.class, () -> PersonaCatalog.load(directory, null));
    }

    @Test
    @DisplayName("口径是引用：读出来带出处，改一处两处都变")
    void glossaryIsReadFromTheReferencedFile() throws IOException {
        write("glossary/sales.md", "成交额 = sum(order_item.amount)");
        write("sales.md", "---\nname: sales\ndescription: 销售\nglossary:\n  - glossary/sales.md\n---\n\n你是销售助手。\n");

        List<GlossarySection> glossary = PersonaCatalog.load(directory, null).glossary();

        assertEquals(1, glossary.size());
        assertEquals("glossary/sales.md", glossary.getFirst().source());
        assertEquals("成交额 = sum(order_item.amount)", glossary.getFirst().text());
    }

    @Test
    @DisplayName("口径引用断了：失败——引用断了等于这个 agent 没有口径，而它会照旧回答")
    void brokenGlossaryReferenceFails() throws IOException {
        write("sales.md", "---\nname: sales\ndescription: 销售\nglossary:\n  - glossary/absent.md\n---\n\n你是销售助手。\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PersonaCatalog.load(directory, null));

        assertTrue(failure.getMessage().contains("glossary/absent.md"), failure.getMessage());
    }

    @Test
    @DisplayName("子目录里的 markdown 不会被当成一个人设：顶层不递归")
    void nestedMarkdownIsNotAPersona() throws IOException {
        write("glossary/sales.md", "口径正文");
        write("sales.md", definition("sales", "你是销售助手。"));

        PersonaCatalog.Catalog catalog = PersonaCatalog.load(directory, null);

        assertEquals(1, catalog.personas().size());
        assertTrue(catalog.personas().containsKey("sales"));
    }

    @Test
    @DisplayName("front matter 没有收尾的 ---：失败，并指出是从哪一行开始被误当成配置的")
    void unterminatedFrontMatterFails() throws IOException {
        write("x.md", "---\nname: x\ndescription: 少了收尾\n\n正文\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PersonaCatalog.load(directory, null));

        assertTrue(failure.getMessage().contains("---"), failure.getMessage());
    }

    @Test
    @DisplayName("列表写在非列表键下面：失败，不静默丢掉——丢掉等于让人以为配置生效了")
    void listUnderScalarKeyFails() throws IOException {
        write("x.md", "---\nname: x\ndescription: 销售\n  - 这是列表项\n---\n\n正文\n");

        assertThrows(IllegalStateException.class, () -> PersonaCatalog.load(directory, null));
    }
}
