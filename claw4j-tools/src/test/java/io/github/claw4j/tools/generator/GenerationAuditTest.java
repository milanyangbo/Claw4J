package io.github.claw4j.tools.generator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计留痕："谁在什么时候跑了什么、结果如何"能被回放。
 *
 * <p>三条被测的性质：<b>只追加</b>（没有改写与删除的接口）、
 * <b>能按包回放</b>（一条链路讲下来）、<b>换一个实例读回来还在</b>（它落盘了，
 * 不是内存里的一份列表——内存里的那份随进程一起消失，而审计存在的理由就是进程会消失）。
 */
class GenerationAuditTest {

    @TempDir
    Path parent;

    @Test
    @DisplayName("按写入顺序留痕，且能原样读回")
    void 记录按顺序留痕() {
        GenerationAudit audit = new GenerationAudit(parent.resolve("audit.tsv"));

        audit.record(Instant.parse("2026-10-24T10:00:00Z"), "ethan",
                GenerationAudit.Stage.GENERATED, "wordcount", "写入候选目录", true);
        audit.record(Instant.parse("2026-10-24T10:00:01Z"), "ethan",
                GenerationAudit.Stage.SELF_TESTED, "wordcount", "退出码 0，输出 12", true);

        List<GenerationAudit.Entry> entries = audit.all();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).stage()).isEqualTo(GenerationAudit.Stage.GENERATED);
        assertThat(entries.get(1).stage()).isEqualTo(GenerationAudit.Stage.SELF_TESTED);
        assertThat(entries.get(0).principal()).isEqualTo("ethan");
        assertThat(entries.get(0).packageName()).isEqualTo("wordcount");
        assertThat(entries.get(0).allowed()).isTrue();
    }

    @Test
    @DisplayName("换一个实例读回来还在：它落盘了，不是内存里的一份列表")
    void 落盘而不是内存() {
        Path file = parent.resolve("persisted.tsv");
        new GenerationAudit(file).record(Instant.parse("2026-10-24T10:00:00Z"), "ethan",
                GenerationAudit.Stage.GENERATED, "wordcount", "x", true);

        assertThat(new GenerationAudit(file).all()).hasSize(1);
    }

    @Test
    @DisplayName("按包回放：一条链路从生成到执行讲下来")
    void 只回放一个包() {
        GenerationAudit audit = new GenerationAudit(parent.resolve("two.tsv"));
        Instant at = Instant.parse("2026-10-24T10:00:00Z");
        audit.record(at, "ethan", GenerationAudit.Stage.GENERATED, "wordcount", "x", true);
        audit.record(at, "ethan", GenerationAudit.Stage.STATIC_REJECTED, "netprobe", "命中 NETWORK_CLIENT", false);

        assertThat(audit.replay("wordcount")).hasSize(1);
        assertThat(audit.replay("netprobe").get(0).allowed()).isFalse();
        assertThat(audit.replay("netprobe").get(0).detail()).contains("NETWORK_CLIENT");
        assertThat(audit.replay("no-such-package")).isEmpty();
    }

    /** 取不到身份时记 anonymous：有一条不知道是谁的记录，比没有记录更接近真相。 */
    @Test
    @DisplayName("没身份时记 anonymous，不记空")
    void 没身份记anonymous() {
        GenerationAudit audit = new GenerationAudit(parent.resolve("anon.tsv"));

        audit.record(Instant.parse("2026-10-24T10:00:00Z"), null, GenerationAudit.Stage.EXECUTED, "pkg", "ok", true);

        assertThat(audit.all().get(0).principal()).isEqualTo("anonymous");
    }

    /** 详情里可以带换行（脚本输出），一行一条的格式得扛得住。 */
    @Test
    @DisplayName("详情里的换行不会把一条记录拆成两条")
    void 换行不会拆行() {
        GenerationAudit audit = new GenerationAudit(parent.resolve("multiline.tsv"));

        audit.record(Instant.parse("2026-10-24T10:00:00Z"), "ethan", GenerationAudit.Stage.SELF_TESTED,
                "pkg", "第一行\n第二行", false);

        assertThat(audit.all()).hasSize(1);
    }

    /** 审计文件不存在时是"还没有记录"，不是失败。 */
    @Test
    @DisplayName("还没有审计文件时是空，不是失败")
    void 没有文件时为空() {
        assertThat(new GenerationAudit(parent.resolve("nope.tsv")).all()).isEmpty();
        assertThat(new GenerationAudit(parent.resolve("nope.tsv")).size()).isZero();
    }
}
