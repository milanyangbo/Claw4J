package io.github.claw4j.agent.rag;

import io.github.claw4j.agent.PermissionFingerprint;
import io.github.claw4j.text.ChineseText;
import io.github.claw4j.text.TextChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 把目录里的文档同步进库。目录是事实来源：文件改了重建、文件删了块也删。
 *
 * <p><b>为什么以目录为准</b>：知识库没有"编辑"入口（模型不该能往里写，那等于给它一个把
 * 提示注入落库的口子）。既然唯一的写入口是文件，那么"库里有、目录里没有"就是一种残留，
 * 留着它会让检索返回一段已经不存在的文档——出处指过去，人打开文件却找不到那段。
 *
 * <p><b>按内容哈希跳过</b>：每次启动都重算全部 embedding，等于每次重启烧一遍 token，
 * 而且块的 ordinal 会与上一次错位（出处会漂）。哈希只覆盖内容，不覆盖路径——
 * 文件改名会被当成"删掉旧的 + 新增一份"，这是对的：出处里的路径变了。
 *
 * <p><b>先算向量再进事务</b>：{@code embed} 是一次网络调用。放在事务里就是"拿着数据库连接
 * 等 HTTP"，一份文档有十几块时会占住连接几十秒。所以先全部算好，再在一个短事务里
 * 删旧块、写新块、更新哈希——中途崩了要么全旧要么全新，不会留下"哈希已更新、块是空的"。
 */
public class KnowledgeIndexer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexer.class);

    private static final String FIND_HASH =
            "select content_hash from claw4j_rag.document where source = ?";

    private static final String UPSERT_DOCUMENT = """
            insert into claw4j_rag.document (source, content_hash)
            values (?, ?)
            on conflict (source) do update
                set content_hash = excluded.content_hash,
                    indexed_at = now()
            returning id
            """;

    private static final String DELETE_CHUNKS =
            "delete from claw4j_rag.chunk where document_id = ?";

    private static final String INSERT_CHUNK = """
            insert into claw4j_rag.chunk (document_id, ordinal, text, bigrams, embedding)
            values (?, ?, ?, ?, ?::vector)
            """;

    private static final String ALL_SOURCES = "select source from claw4j_rag.document";

    private static final String DELETE_DOCUMENT = "delete from claw4j_rag.document where source = ?";

    private static final String COUNT_CHUNKS = "select count(*) from claw4j_rag.chunk";

    private final JdbcTemplate jdbc;

    private final EmbeddingModel embeddings;

    private final RagProperties properties;

    private final TransactionTemplate transactions;

    public KnowledgeIndexer(JdbcTemplate jdbc, EmbeddingModel embeddings, RagProperties properties,
                            PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    }

    /**
     * 一次同步的结果。
     *
     * @param indexed 重新索引的文档数（新增或内容变了）
     * @param skipped 内容没变、跳过的文档数
     * @param removed 目录里已经没有、从库里删掉的文档数
     * @param chunks  同步之后库里一共多少块
     */
    public record Stats(int indexed, int skipped, int removed, int chunks) {
    }

    /**
     * 同步一次。
     *
     * @throws IllegalStateException 目录不存在、不是目录、或者里面一个可索引文件都没有。
     *        这三种都不该"安静地成功"——它们的结果是一个空知识库，而空知识库的表现是
     *        每一次检索都返回"没有相关资料"，与"这个问题确实没写进文档"一模一样。
     */
    public Stats index() {
        if (!properties.hasDirectory()) {
            throw new IllegalStateException(
                    "claw4j.rag.enabled=true 但没配 claw4j.rag.directory：知识库没有文档来源");
        }
        Path root = Path.of(properties.directory());
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("claw4j.rag.directory 不是一个目录：" + root.toAbsolutePath());
        }
        List<Path> files = documentsOf(root);
        if (files.isEmpty()) {
            throw new IllegalStateException("claw4j.rag.directory 里没有可索引的 .md / .txt 文件："
                    + root.toAbsolutePath() + "。空知识库会让每次检索都答「没有相关资料」，"
                    + "而那与「这段确实没写」分不开——所以这里直接失败");
        }

        int indexed = 0;
        int skipped = 0;
        Set<String> present = new HashSet<>();
        for (Path file : files) {
            String source = sourceOf(root, file);
            present.add(source);
            String content = read(file);
            String hash = PermissionFingerprint.sha256(content);
            if (hash.equals(existingHash(source))) {
                skipped++;
                continue;
            }
            indexOne(source, content, hash);
            indexed++;
        }
        int removed = removeMissing(present);
        int chunks = chunkCount();
        log.info("知识库同步完成：{} 份文档里重建 {} 份、跳过 {} 份、删掉 {} 份，现有 {} 段（目录 {}）",
                files.size(), indexed, skipped, removed, chunks, root.toAbsolutePath());
        return new Stats(indexed, skipped, removed, chunks);
    }

    /** 拼出路径、切块、算向量，然后在一个短事务里换掉这份文档的全部块。 */
    private void indexOne(String source, String content, String hash) {
        List<TextChunker.Chunk> chunks = TextChunker.split(content, properties.maxChars());
        if (chunks.isEmpty()) {
            // 文件只有空白：把它当作"存在但没有内容"。**不写空块**，也不当失败——
            // 一个还没写内容的 placeholder 文件不该让整个进程起不来。
            log.warn("{} 里没有可索引的内容（只有空白），跳过", source);
            return;
        }
        List<ReadyChunk> ready = new ArrayList<>(chunks.size());
        for (TextChunker.Chunk chunk : chunks) {
            float[] embedding = embeddings.embed(chunk.text());
            if (embedding == null) {
                throw new IllegalStateException("算不出 " + source + " 第 " + chunk.ordinal()
                        + " 段的向量：embedding 服务返回空。索引没建起来，不能假装建起来了");
            }
            ready.add(new ReadyChunk(chunk, embedding));
        }

        transactions.executeWithoutResult(status -> {
            Long documentId = jdbc.queryForObject(UPSERT_DOCUMENT, Long.class, source, hash);
            if (documentId == null) {
                throw new IllegalStateException("写不进文档记录：" + source);
            }
            // 先删后插：块的 ordinal 会随内容变化而整体前移，逐条 upsert 会留下上次的尾巴。
            jdbc.update(DELETE_CHUNKS, documentId);
            for (ReadyChunk chunk : ready) {
                jdbc.update(INSERT_CHUNK, documentId, chunk.chunk().ordinal(), chunk.chunk().text(),
                        ChineseText.bigrams(chunk.chunk().text()), vectorLiteral(chunk.embedding()));
            }
        });
    }

    private int removeMissing(Set<String> present) {
        List<String> stored = jdbc.queryForList(ALL_SOURCES, String.class);
        int removed = 0;
        for (String source : stored) {
            if (!present.contains(source)) {
                jdbc.update(DELETE_DOCUMENT, source);
                removed++;
            }
        }
        return removed;
    }

    private String existingHash(String source) {
        List<String> hashes = jdbc.queryForList(FIND_HASH, String.class, source);
        return hashes.isEmpty() ? null : hashes.getFirst();
    }

    private int chunkCount() {
        Integer count = jdbc.queryForObject(COUNT_CHUNKS, Integer.class);
        return count == null ? 0 : count;
    }

    /** 目录下的 {@code .md} / {@code .txt}，按路径排序——顺序稳定，日志与用例才好比对。 */
    private static List<Path> documentsOf(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> isDocument(path.getFileName().toString()))
                    .sorted()
                    .toList();
        }
        catch (IOException failure) {
            throw new UncheckedIOException("读不了目录 " + root.toAbsolutePath(), failure);
        }
    }

    private static boolean isDocument(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".txt");
    }

    /** 出处里的路径用正斜杠：Windows 上跑出来的 {@code \} 放进 URL 或 JSON 都要再转义。 */
    private static String sourceOf(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        catch (IOException failure) {
            throw new UncheckedIOException("读不了文档 " + file.toAbsolutePath(), failure);
        }
    }

    /** 与 {@link PgVectorKnowledgeBase} 里那份同形；两处的理由见那边的注释。 */
    private static String vectorLiteral(float[] embedding) {
        StringBuilder literal = new StringBuilder(embedding.length * 12 + 2).append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(embedding[i]);
        }
        return literal.append(']').toString();
    }

    /** 已经算好向量的一块，进事务之前就准备好。 */
    private record ReadyChunk(TextChunker.Chunk chunk, float[] embedding) {
    }
}
