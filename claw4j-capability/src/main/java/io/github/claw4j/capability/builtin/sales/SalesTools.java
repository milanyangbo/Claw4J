package io.github.claw4j.capability.builtin.sales;

import io.github.claw4j.capability.builtin.sales.QueryResult;
import io.github.claw4j.capability.builtin.sales.ReadOnlySqlExecutor;
import io.github.claw4j.capability.builtin.sales.SalesSqlGuard;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.Objects;

/**
 * 销售域查询工具，能力三层里的第一层（Java {@code @Tool}，ADR-0003）。
 *
 * <p><b>这是 Text-to-SQL 的入口</b>：模型把自然语言问题变成一条 SQL 交进来，
 * 这里只读执行、把行还回去，由模型自己组织成回答。
 * 工具不替模型决定查什么——那正是 Text-to-SQL 的含义所在。
 *
 * <p>三个刻意的设计：
 *
 * <ul>
 *   <li><b>schema 写进工具描述里</b>（见 {@code DESCRIPTION} 里的 SCHEMA 段）。
 *       固定 schema 是 ADR-0008 必测项"Text-to-SQL 自纠错链路用固定 schema 跑通"的
 *       另一半：模型不知道表名列名，就只能在错误里瞎猜，自纠错永远收敛不了。
 *       描述里连区域代码的取值（{@code east} / {@code south} / {@code north}）都给了，
 *       因为写 {@code where region_code = '华东'} 不会报错、只会返回 0 行，
 *       而"返回 0 行"是自纠错唯一无法自己发现的错误。
 *   <li><b>失败返回原因而不是抛异常</b>。抛异常会让框架把错误包成模型看不懂的话；
 *       返回带原因的字符串，模型才有东西可改。这是自纠错链路能成立的前提。
 *   <li><b>这个类不认识数据库</b>。执行走 {@link ReadOnlySqlExecutor} 这个接缝，
 *       于是循环行为（自纠错、调用上限）能在没有 Docker 的地方被测完整。
 * </ul>
 *
 * <p>它也不认识 Agent 与通道：只认参数、只给返回值。一旦工具里出现"查一下当前用户是谁"
 * 或者"回一条消息"，它就长进 Agent 里了，从此只能在 Agent 的上下文里测
 * （见 {@code ToolsArchitectureTest}）。
 */
public class SalesTools {

    /** 固定 schema。与 {@code V1__business_schema.sql} 一一对应，改表结构时两处都要动。 */
    private static final String SCHEMA = """
            可用的表（都在 business schema 下）：
              business.region(code, name)                       -- code 取值：east=华东, south=华南, north=华北
              business.customer(id, code, name, region_code)
              business.sales_order(id, order_no, customer_id, region_code, order_date, status)
                                                                -- status 取值：PAID, SHIPPED, CANCELLED
              business.order_item(id, order_id, product_name, quantity, unit_price, amount)
                                                                -- amount = quantity * unit_price，已由数据库算好
            成交额口径：sum(order_item.amount) 且 status <> 'CANCELLED'（已取消的订单不计入）。
            时间窗一律用半开区间 [起始, 结束)：
              上周 = order_date >= date_trunc('week', current_date) - interval '7 days'
                     and order_date <  date_trunc('week', current_date)
            """;

    private static final String DESCRIPTION = "把关于销售额的自然语言问题翻译成一条只读 SELECT 语句并执行，"
            + "返回查询结果的行。只接受单条 select / with 语句；insert、update、delete、drop "
            + "等会改写数据的语句一律被拒绝。一次最多返回 " + SalesSqlGuard.MAX_ROWS + " 行。\n" + SCHEMA;

    /**
     * 被拒或报错时附在后面的改写提示。自纠错靠的就是这几句：
     * 没有它，模型拿到"执行失败"只会原样再写一遍同样的 SQL。
     */
    private static final String CORRECTION_HINT =
            " 请修正 SQL 后重新调用本工具：区域要用代码（east/south/north）而不是中文名，"
                    + "时间窗用 order_date 的半开区间，只写一条 select 语句。";

    private static final String EMPTY_HINT =
            " 如果这不是预期结果：检查 region_code 是否用了代码（east/south/north）而不是中文名，"
                    + "以及时间窗是否覆盖到了 order_date。";

    private final ReadOnlySqlExecutor executor;

    public SalesTools(ReadOnlySqlExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * 执行一条只读 SQL，把结果行交回给模型。
     *
     * <p><b>为什么参数是整条 SQL 而不是"区域 + 时间窗"两个字段</b>：这是 Text-to-SQL，
     * 翻译工作归模型。把参数收成两个字段，模型就退化成填空机，
     * "上周华东区成交额"之外的问法（按产品、按客户、环比）全都答不了。
     */
    @Tool(description = DESCRIPTION)
    public String querySales(
            @ToolParam(description = "一条只读 SELECT 语句。必须是单条语句，且不能改写数据。") String sql) {
        var rejection = SalesSqlGuard.rejectionOf(sql);
        if (rejection.isPresent()) {
            return "这条 SQL 没有通过只读校验：" + rejection.get() + "。" + CORRECTION_HINT;
        }

        try {
            return render(executor.execute(sql));
        }
        catch (DataAccessException failure) {
            return "这条 SQL 执行失败：" + describe(failure) + "。" + CORRECTION_HINT;
        }
    }

    /**
     * 数据库为什么拒绝。取 root cause 而不是最外层异常的消息：
     * Spring 会把 PostgreSQL 的原话包进好几层 {@code BadSqlGrammarException}，
     * 最外层那句只有"prepared statement 执行失败"，模型拿到它等于没拿到任何信息——
     * 真正有用的是最里面那句"column x does not exist"。
     */
    private static String describe(DataAccessException failure) {
        Throwable root = failure.getRootCause() == null ? failure : failure.getRootCause();
        String message = root.getMessage();
        return message == null ? failure.getClass().getSimpleName() : message;
    }

    /**
     * 行怎么变成模型能读的东西。刻意用竖线分隔而不是 Markdown 表格：
     * 表格在提示词里占的 token 是纯文本的三倍以上，而模型读这两种格式没有差别。
     */
    private String render(QueryResult result) {
        if (result.rowCount() == 0) {
            return "查询成功，返回 0 行。" + EMPTY_HINT;
        }

        StringBuilder text = new StringBuilder(String.join(" | ", result.columns())).append('\n');
        for (List<String> row : result.rows()) {
            text.append(String.join(" | ", row)).append('\n');
        }
        if (result.truncated()) {
            text.append("（结果超过 ").append(SalesSqlGuard.MAX_ROWS)
                    .append(" 行，这里只给出前 ").append(SalesSqlGuard.MAX_ROWS)
                    .append(" 行。要合计或排名请改用聚合查询，不要试图翻页。）\n");
        }
        return text.toString();
    }
}
