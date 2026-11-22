package io.github.claw4j.agent.progress;

import org.springframework.jdbc.core.JdbcTemplate;
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
 * 进度卡的唯一实现：一行，整卡替换。
 *
 * <p>为什么是替换而不是增量：卡表达的是"现在是什么样"，不是"改过几次"。增量补丁一旦漏一条，
 * 后面所有人都按错的那份理解进度，而错的那份看起来完全正常。它自己也写明 "Every call is a
 * replacement, not a patch."
 *
 * <p>空计划 = 删行，而不是写一份空卡：读的一侧只需要处理"有卡 / 没卡"两种情况。
 */
public final class JdbcProgressCards implements ProgressCards {

    private static final String UPSERT = """
            insert into claw4j_session.progress_card
                (session_id, plan_json, step_count, in_progress, updated_at)
            values (?, ?, ?, ?, ?)
            on conflict (session_id) do update
               set plan_json   = excluded.plan_json,
                   step_count  = excluded.step_count,
                   in_progress = excluded.in_progress,
                   updated_at  = excluded.updated_at
            """;

    private static final String DELETE = "delete from claw4j_session.progress_card where session_id = ?";

    private static final String SELECT =
            "select plan_json, updated_at from claw4j_session.progress_card where session_id = ?";

    private final JdbcTemplate jdbc;

    private final ObjectMapper json = JsonMapper.builder().build();

    /**
     * @param jdbc 主连接池；表名全限定，不依赖 {@code currentSchema}
     */
    public JdbcProgressCards(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void save(String sessionId, List<Step> steps) {
        Objects.requireNonNull(sessionId, "sessionId");
        List<Step> checked = ProgressCards.checked(steps);
        if (checked.isEmpty()) {
            jdbc.update(DELETE, sessionId);
            return;
        }
        long inProgress = checked.stream().filter(s -> s.status() == Status.IN_PROGRESS).count();
        jdbc.update(UPSERT, sessionId, write(checked), checked.size(), (int) inProgress,
                Timestamp.from(Instant.now()));
    }

    @Override
    public Optional<Card> of(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query(SELECT, (rs, row) -> {
            OffsetDateTime at = rs.getObject("updated_at", OffsetDateTime.class);
            return new Card(sessionId, read(rs.getString("plan_json")), at.toInstant());
        }, sessionId).stream().findFirst();
    }

    private String write(List<Step> steps) {
        try {
            return json.writeValueAsString(steps);
        } catch (JacksonException e) {
            // 序列化失败不是模型的问题，不该变成一句"你的计划不合格"。
            throw new IllegalStateException("进度卡序列化失败", e);
        }
    }

    private List<Step> read(String planJson) {
        try {
            Step[] steps = json.readValue(planJson, Step[].class);
            return steps == null ? List.of() : List.of(steps);
        } catch (JacksonException e) {
            // 库里躺着一份读不出来的计划：当成没有，别让投递面整页 500。
            return List.of();
        }
    }
}
