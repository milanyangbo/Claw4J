package io.github.claw4j.tools;

import io.github.claw4j.tools.sales.QueryResult;
import io.github.claw4j.tools.sales.ReadOnlySqlExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Tool} 方法接缝：给定参数，断言返回什么（ADR-0008 的"脱离模型，
 * 单独定位 SQL 执行"）。
 *
 * <p>执行器是替身，所以这里测的是"工具这一层的行为"，与数据库无关、与模型无关：
 * 校验发生在执行之前、失败要给出模型能改的原因、行怎么变成文本。
 * SQL 对不对是 {@code ReadOnlySqlExecutorTest} 的事，模型会不会改是
 * {@code TextToSqlLoopTest} 的事。
 */
class SalesToolsTest {

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

    private static final String GOOD_SQL = "select sum(oi.amount) as amount from business.order_item oi";

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
}
