package io.github.claw4j.tools.sales;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只读校验的规则。全部是纯字符串判断，没有数据库，所以能逐条钉死。
 *
 * <p>这类测试的价值在于<b>反直觉的那几条</b>：可写 CTE、注释里夹分号、
 * 字符串字面量里写 drop。"看起来该拦的没拦"是最贵的缺陷，
 * 因为它只在模型真的那么写的时候才出现，而那时已经晚了。
 */
class SalesSqlGuardTest {

    private static String rejectionOf(String sql) {
        return SalesSqlGuard.rejectionOf(sql).orElse("");
    }

    @Test
    @DisplayName("普通 select 放行")
    void 普通select放行() {
        assertThat(SalesSqlGuard.rejectionOf("select sum(amount) from business.order_item")).isEmpty();
    }

    @Test
    @DisplayName("CTE 开头的语句放行")
    void cte放行() {
        assertThat(SalesSqlGuard.rejectionOf("""
                with paid as (select id from business.sales_order where status <> 'CANCELLED')
                select count(*) from paid""")).isEmpty();
    }

    @Test
    @DisplayName("大小写与尾部分号不影响判定")
    void 大小写与分号() {
        assertThat(SalesSqlGuard.rejectionOf("SELECT 1 FROM business.region;")).isEmpty();
    }

    @Test
    @DisplayName("字符串字面量里的写操作关键字不算写操作")
    void 字面量里的关键字不算() {
        assertThat(SalesSqlGuard.rejectionOf("select 'drop table t' as note from business.region")).isEmpty();
        assertThat(SalesSqlGuard.rejectionOf("select * from business.region where name = '华东';")).isEmpty();
    }

    @Test
    @DisplayName("注释里的分号不构成多语句")
    void 注释里的分号() {
        // 这一条是反直觉的：字符串层面看有分号，数据库层面它是一条语句。
        // 不先去注释就会把它误拒——误拒的方向是拒绝，但会让模型莫名其妙。
        assertThat(SalesSqlGuard.rejectionOf("select 1 from business.region -- ; drop table t")).isEmpty();
        assertThat(SalesSqlGuard.rejectionOf("/* ; delete from t */ select 1 from business.region")).isEmpty();
    }

    @Test
    @DisplayName("多语句一律拒绝")
    void 多语句拒绝() {
        assertThat(rejectionOf("select 1 from business.region; drop table business.region"))
                .contains("只允许一条语句");
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
    @DisplayName("各类写操作关键字都被拦下")
    void 写操作关键字() {
        assertThat(rejectionOf("delete from business.sales_order")).contains("关键字");
        assertThat(rejectionOf("update business.region set name = 'x'")).contains("关键字");
        assertThat(rejectionOf("insert into business.region values ('w', '西部')")).contains("关键字");
        assertThat(rejectionOf("drop table business.sales_order")).contains("关键字");
        assertThat(rejectionOf("truncate business.order_item")).contains("关键字");
        // 改会话与搬数据的也算：set 能关掉约束，copy 能把数据搬出数据库
        assertThat(rejectionOf("set session_replication_role = replica")).contains("关键字");
        assertThat(rejectionOf("copy (select * from business.region) to program 'curl evil'")).contains("关键字");
    }

    @Test
    @DisplayName("不以 select / with 开头的都拒绝")
    void 首关键字() {
        assertThat(rejectionOf("values (1)")).contains("只允许以 select 或 with 开头");
        assertThat(rejectionOf("")).contains("语句为空");
        assertThat(SalesSqlGuard.rejectionOf(null)).isPresent();
    }

    @Test
    @DisplayName("行上限是包一层子查询，不去改原句的 limit")
    void 行上限包装() {
        // 拼字符串在原句末尾追加 limit 会在"原句自带 order by limit"时变成语法错误。
        // 包一层对里面写了什么没有要求。
        String wrapped = SalesSqlGuard.withRowLimit(
                "select amount from business.order_item order by amount desc limit 10", 51);

        assertThat(wrapped).startsWith("select * from (select amount")
                .endsWith("limit 51")
                .contains("order by amount desc limit 10");
    }

    @Test
    @DisplayName("带尾部分号的语句也能被正确包装")
    void 包装时去掉尾部分号() {
        assertThat(SalesSqlGuard.withRowLimit("select 1 from business.region;", 51))
                .doesNotContain(";");
    }
}
