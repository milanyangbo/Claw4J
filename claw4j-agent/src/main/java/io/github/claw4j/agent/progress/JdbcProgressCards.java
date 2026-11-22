package io.github.claw4j.agent.progress;

import io.github.claw4j.agent.session.SessionSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 进度卡的唯一实现：一行，整卡替换，写一次 {@code revision} 加一。
 *
 * <p>为什么是替换而不是增量：卡表达的是"现在是什么样"，不是"改过几次"。增量补丁一旦漏一条，
 * 后面所有人都按错的那份理解进度，而错的那份看起来完全正常。它自己也写明 "Every call is a
 * replacement, not a patch."
 *
 * <p>清空不删行：内容置 null、{@code revision + 1}。这与它的 "retain revision tombstones" 一致，
 * 也是乐观并发能工作的前提——版本号不能因为"清空了"就回到原点。
 */
public final class JdbcProgressCards implements ProgressCards {

    private final String insertOrReplace;

    private final String insertOrReplaceIfRevision;

    private final String clear;

    private final String clearIfRevision;

    private final String select;

    private final JdbcTemplate jdbc;

    private final ObjectMapper json = JsonMapper.builder().build();

    /**
     * @param jdbc   主连接池
     * @param schema 会话 schema 名（来自配置）。表名靠
     *               {@link SessionSchema#qualifier(String)} 拼，不写死字面量——
     *               写死之后改配会继续指向旧 schema，而症状是功能悄悄失效
     */
    public JdbcProgressCards(JdbcTemplate jdbc, String schema) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        String table = SessionSchema.qualifier(schema) + "session_progress_cards";
        this.insertOrReplace = """
                insert into %s
                    (session_key, markdown, steps_json, revision, created_at, updated_at)
                values (?, ?, ?, 1, ?, ?)
                on conflict (session_key) do update
                   set markdown   = excluded.markdown,
                       steps_json = excluded.steps_json,
                       revision   = %s.revision + 1,
                       updated_at = excluded.updated_at
                """.formatted(table, table);
        // 带期望版本的一版：版本不符时 where 不成立，影响 0 行。
        this.insertOrReplaceIfRevision = insertOrReplace + " where %s.revision = ?".formatted(table);
        this.clear = """
                update %s
                   set markdown = null, steps_json = null,
                       revision = revision + 1, updated_at = ?
                 where session_key = ?
                """.formatted(table);
        this.clearIfRevision = clear
                + "   and revision = ?\n"
                + "   and (markdown is not null or steps_json is not null)";
        this.select = """
                select session_key, markdown, steps_json, revision, created_at, updated_at
                  from %s
                 where session_key = ?
                """.formatted(table);
    }

    @Override
    public Outcome save(String sessionKey, Write write) {
        Objects.requireNonNull(sessionKey, "sessionKey");
        Objects.requireNonNull(write, "write");
        String markdown = write.markdown() == null || write.markdown().isBlank()
                ? null : write.markdown().strip();
        List<Step> steps = ProgressCards.checked(write.steps());
        Long expected = write.expectedRevision();
        Timestamp now = Timestamp.from(Instant.now());

        if (markdown == null && steps.isEmpty()) {
            return clear(sessionKey, expected, now);
        }
        int changed = expected == null
                ? jdbc.update(insertOrReplace, sessionKey, markdown, stepsJson(steps), now, now)
                : jdbc.update(insertOrReplaceIfRevision, sessionKey, markdown, stepsJson(steps),
                        now, now, expected);
        if (changed == 0) {
            return stale(sessionKey);
        }
        return new Outcome.Saved(read(sessionKey).orElseThrow(() -> new IllegalStateException(
                "刚写进去就查不到：" + sessionKey)));
    }

    @Override
    public Optional<Card> of(String sessionKey) {
        return read(sessionKey).filter(JdbcProgressCards::visible);
    }

    /**
     * 清空：置 null 并 +1，不删行。
     *
     * @param sessionKey 会话
     * @param expected   期望版本，可为 null
     * @param now        时间
     * @return 结果
     */
    private Outcome clear(String sessionKey, Long expected, Timestamp now) {
        int changed = expected == null
                ? jdbc.update(clear, now, sessionKey)
                : jdbc.update(clearIfRevision, now, sessionKey, expected);
        if (changed == 0) {
            // 两种可能：行不存在（本来就没有卡），或版本不符（含"已经清空过"）。
            return read(sessionKey).isPresent() ? stale(sessionKey) : new Outcome.Cleared();
        }
        return new Outcome.Cleared();
    }

    /**
     * 没写进去，把库里当前的返回去。
     *
     * @param sessionKey 会话
     * @return {@link Outcome.Stale}；库里已无行时按"已清空"处理
     */
    private Outcome stale(String sessionKey) {
        return read(sessionKey)
                .<Outcome>map(Outcome.Stale::new)
                .orElseGet(Outcome.Cleared::new);
    }

    /**
     * 读一行（含墓碑）。
     *
     * @param sessionKey 会话
     * @return 行在就有值，不判空内容
     */
    private Optional<Card> read(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return Optional.empty();
        }
        RowMapper<Card> mapper = (rs, row) -> new Card(
                rs.getString("session_key"),
                rs.getLong("revision"),
                rs.getString("markdown"),
                parse(rs.getString("steps_json")),
                instantOf(rs.getObject("created_at", OffsetDateTime.class)),
                instantOf(rs.getObject("updated_at", OffsetDateTime.class)));
        return jdbc.query(select, mapper, sessionKey).stream().findFirst();
    }

    private static Instant instantOf(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * 有内容才算一张卡。它读出来时也是这个判据（{@code !markdown && !steps?.length → null}）。
     *
     * @param card 行
     * @return 是否可见
     */
    private static boolean visible(Card card) {
        boolean hasMarkdown = card.markdown() != null && !card.markdown().isBlank();
        return hasMarkdown || !card.steps().isEmpty();
    }

    private String stepsJson(List<Step> steps) {
        try {
            return json.writeValueAsString(steps);
        } catch (JacksonException e) {
            throw new IllegalStateException("进度卡序列化失败", e);
        }
    }

    private List<Step> parse(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return List.of();
        }
        try {
            Step[] steps = json.readValue(stepsJson, Step[].class);
            return steps == null ? List.of() : List.of(steps);
        } catch (JacksonException e) {
            // 库里躺着一份读不出来的计划：当成没有，别让投递面整页 500。
            return List.of();
        }
    }
}
