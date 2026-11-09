package io.github.claw4j.agent.rag;

import io.github.claw4j.agent.PermissionFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 把目录里的文档同步进知识库。目录是事实来源：文件改了重建、文件删了块也删。
 *
 * <p><b>为什么以目录为准</b>：知识库没有"编辑"入口（模型不该能往里写，那等于给它一个把
 * 提示注入落库的口子）。既然唯一的写入口是文件，那么"库里有、目录里没有"就是一种残留，
 * 留着它会让检索返回一段已经不存在的文档——出处指过去，人打开文件却找不到那段。
 *
 * <p><b>按内容哈希跳过</b>：靠 {@code claw4j_rag.source} 那张表（照 OpenClaw 的
 * {@code memory_index_sources}）。框架的 {@code VectorStore} 没有"这份文件上次是什么样"的账，
 * 所以这张表是我们必须自己留的一张——没有它，每次启动都要把全部文档重算一遍 embedding。
 *
 * <p><b>但哈希不是唯一的判据</b>：切块实现变了，内容没变的文件也得重建。这件事靠
 * {@code claw4j_rag.index_meta} 一行身份（照 OpenClaw 的 {@code memory_index_meta}）——
 * 身份不一致就全量重建，而不是每行挂一个版本号。
 *
 * <p><b>写入全部走框架</b>：切块见 {@link SemanticTextSplitter}（ADR-0028），写块与删块是
 * {@code VectorStore.add} / {@code delete(filter)}。不再有手拼的 insert/delete。
 *
 * <p><b>没有事务，靠"哈希最后写"保证可重来</b>：一份文档的顺序是
 * 删旧块 → 加新块 → 更新哈希。中间任何一步失败，哈希仍是旧的，下次同步会重来一遍。
 * 这样做的代价要说清：在"旧块已删、新块还没写完"的窗口里，这份文档检索不到——
 * 而它只发生在启动同步期间，那时没有请求在查。反过来（把 embedding 算在事务里）
 * 会拿着数据库连接等 HTTP，一份长文档就占住连接几十秒。
 */
public class KnowledgeIndexer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexer.class);

    /** 来源类别。今天只有一处文档目录；写进键里是为了将来多接一份目录时不用改表。 */
    static final String SOURCE_KIND = "docs";

    private static final String FIND_HASH =
            "select content_hash from claw4j_rag.source where path = ? and source = ?";

    private static final String UPSERT_SOURCE = """
            insert into claw4j_rag.source (path, source, content_hash, mtime, size, indexed_at)
            values (?, ?, ?, ?, ?, now())
            on conflict (path, source) do update
                set content_hash = excluded.content_hash,
                    mtime = excluded.mtime,
                    size = excluded.size,
                    indexed_at = now()
            """;

    private static final String ALL_PATHS =
            "select path from claw4j_rag.source where source = ?";

    private static final String DELETE_SOURCE =
            "delete from claw4j_rag.source where path = ? and source = ?";

    private static final String COUNT_CHUNKS =
            "select count(*) from " + RagAutoConfiguration.VECTOR_SCHEMA + "."
                    + RagAutoConfiguration.VECTOR_TABLE;

    /** 索引身份的 key。照 OpenClaw 的 {@code memory_index_meta_v1}：一行 key + 一个 JSON 值。 */
    public static final String META_KEY = "index_meta_v1";

    /**
     * 切块实现的版本。
     *
     * <p>**改切块算法、词表、阈值、最少句数都要加一**：身份一变，下次启动就全量重建。
     * 不这样做的话，hash 没变的文件永远不重建，库里会混着两套边界的块且没有任何迹象。
     */
    static final int CHUNKING_VERSION = 1;

    private static final String FIND_META =
            "select value from " + RagAutoConfiguration.VECTOR_SCHEMA + ".index_meta where key = ?";

    private static final String UPSERT_META =
            "insert into " + RagAutoConfiguration.VECTOR_SCHEMA + ".index_meta (key, value) values (?, ?) "
                    + "on conflict (key) do update set value = excluded.value";

    private final JdbcTemplate jdbc;

    private final VectorStore vectorStore;

    private final RagProperties properties;

    /**
     * 切块器。**两级**：句子之间按语义断（转折词 + 向量跃迁），块超 token 上限才硬切。
     * 裁决见 ADR-0028；算法在 `claw4j-core` 的 {@code ChunkPlanner}。
     */
    private final TextSplitter splitter;

    public KnowledgeIndexer(JdbcTemplate jdbc, VectorStore vectorStore, EmbeddingModel embeddings,
                            RagProperties properties) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.splitter = new SemanticTextSplitter(Objects.requireNonNull(embeddings, "embeddings"),
                properties.chunkSize());
    }

    /**
     * 索引身份：切块实现版本 + token 上限。
     *
     * <p>写进 {@code claw4j_rag.index_meta} 一行（照 OpenClaw 的 {@code memory_index_meta}），
     * 值是一个固定的 JSON 串——只有我们写它，所以按字符串比较就够，不需要再解析一遍。
     * {@code chunkTokens} 也进身份，因为改 {@code chunk-size} 同样会改变块边界。
     */
    public static String identityOf(int chunkSize) {
        return "{\"chunkingVersion\":" + CHUNKING_VERSION + ",\"chunkTokens\":" + chunkSize + "}";
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

        String identity = identityOf(properties.chunkSize());
        boolean rebuildAll = !identity.equals(storedIdentity());
        if (rebuildAll) {
            log.info("知识库切块身份与库里不一致（或还没有身份），这次全量重建：{}", identity);
        }

        int indexed = 0;
        int skipped = 0;
        Set<String> present = new HashSet<>();
        for (Path file : files) {
            String path = pathOf(root, file);
            present.add(path);
            String content = read(file);
            String hash = PermissionFingerprint.sha256(content);
            // 身份不符时**不看 hash**：切块算法变了，内容没变也要按新边界重建。
            if (!rebuildAll && hash.equals(existingHash(path))) {
                skipped++;
                continue;
            }
            indexOne(path, content, hash, file);
            indexed++;
        }
        int removed = removeMissing(present);
        // 身份最后写：任何一份文档失败，它仍是旧值，下次启动会再全量重来一遍。
        if (rebuildAll) {
            writeIdentity(identity);
        }
        int chunks = chunkCount();
        log.info("知识库同步完成：{} 份文档里重建 {} 份、跳过 {} 份、删掉 {} 份，现有 {} 段"
                        + "（切块 {}，目录 {}）",
                files.size(), indexed, skipped, removed, chunks, identity, root.toAbsolutePath());
        return new Stats(indexed, skipped, removed, chunks);
    }

    /**
     * 重建一份文档：删旧块 → 加新块 → 最后写哈希。
     *
     * <p><b>哈希最后写</b>：它是"这份文档已经完整进库"的标志。先写哈希再写块的话，
     * 中间失败会留下一条"hash 说已索引、块却只有一半"的记录，而下次同步会跳过它——
     * 知识库从此永久缺一段，且没有任何迹象。
     */
    private void indexOne(String path, String content, String hash, Path file) {
        List<Document> chunks = chunk(path, content, hash);
        if (chunks.isEmpty()) {
            // 文件只有空白：把它当作"存在但没有内容"。**不写空块**，也不当失败——
            // 一个还没写内容的 placeholder 文件不该让整个进程起不来。
            log.warn("{} 里没有可索引的内容（只有空白），跳过", path);
            return;
        }
        // 先删后加：块的编号会随内容变化而整体前移，不删的话上次的尾巴还在库里，
        // 检索会返回一段已经不存在的原文。
        deleteChunksOf(path);
        vectorStore.add(chunks);
        jdbc.update(UPSERT_SOURCE, path, SOURCE_KIND, hash, mtimeOf(file), sizeOf(file));
    }

    /**
     * 删掉一份文档的全部块。
     *
     * <p>按 metadata 过滤而不是按 id：一块一块按 id 删要求我们把上次的 id 都记住，
     * 而那需要另一张表（并且那张表会与 vector_store 不一致）。过滤条件是这份文档的身份，
     * 不依赖上一次写入记了什么。
     */
    private void deleteChunksOf(String path) {
        FilterExpressionBuilder filters = new FilterExpressionBuilder();
        vectorStore.delete(filters.and(
                filters.eq(RagMetadata.PATH, path),
                filters.eq(RagMetadata.SOURCE, SOURCE_KIND)).build());
    }

    /**
     * 切块并给每块装上出处。
     *
     * <p><b>为什么 metadata 重新造而不是沿用切块器给的</b>：{@code TokenTextSplitter} 会往
     * metadata 里塞它自己的 {@code chunk_index} 之类的键。沿用它意味着"第几段"有两套口径
     * （它从 0 起、我们对外从 1 起），而出处是要给人读的，两套口径迟早对不上。
     */
    private List<Document> chunk(String path, String content, String hash) {
        List<Document> pieces = splitter.apply(List.of(new Document(content)));
        List<Document> chunks = new ArrayList<>(pieces.size());
        int index = 0;
        for (Document piece : pieces) {
            if (piece.getText() == null || piece.getText().isBlank()) {
                continue;
            }
            index++;
            chunks.add(new Document(piece.getText(),
                    RagMetadata.of(path, SOURCE_KIND, String.valueOf(index), hash)));
        }
        return chunks;
    }

    private int removeMissing(Set<String> present) {
        List<String> stored = jdbc.queryForList(ALL_PATHS, String.class, SOURCE_KIND);
        int removed = 0;
        for (String path : stored) {
            if (!present.contains(path)) {
                deleteChunksOf(path);
                jdbc.update(DELETE_SOURCE, path, SOURCE_KIND);
                removed++;
            }
        }
        return removed;
    }

    private String existingHash(String path) {
        List<String> hashes = jdbc.queryForList(FIND_HASH, String.class, path, SOURCE_KIND);
        return hashes.isEmpty() ? null : hashes.getFirst();
    }

    /** 库里记着的切块身份。没有这一行（升级前、或第一次跑）返回 {@code null}，按"全量重建"处理。 */
    private String storedIdentity() {
        List<String> values = jdbc.queryForList(FIND_META, String.class, META_KEY);
        return values.isEmpty() ? null : values.getFirst();
    }

    private void writeIdentity(String identity) {
        jdbc.update(UPSERT_META, META_KEY, identity);
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
    private static String pathOf(Path root, Path file) {
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

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        }
        catch (IOException failure) {
            // 与 mtime 同理：字节数只用于排障，读不到就当没有。
            log.debug("读不到 {} 的字节数", file, failure);
            return 0L;
        }
    }

    /**
     * 文件的修改时刻。
     *
     * <p><b>返回 {@code OffsetDateTime} 而不是 {@code Instant}</b>：pgjdbc 不认 Instant，
     * 传进去会报 "Can't infer the SQL type to use for an instance of java.time.Instant"——
     * 而那条错在启动同步时才出现，看起来像 SQL 语法问题。其它几处写 timestamptz 的地方
     * 用的是同一个类型，口径一致。
     */
    private static OffsetDateTime mtimeOf(Path file) {
        try {
            FileTime time = Files.getLastModifiedTime(file);
            return OffsetDateTime.ofInstant(time.toInstant(), ZoneOffset.UTC);
        }
        catch (IOException failure) {
            // mtime 只用于排障（hash 才是判据），读不到就当没有，不必让同步失败。
            return null;
        }
    }
}
