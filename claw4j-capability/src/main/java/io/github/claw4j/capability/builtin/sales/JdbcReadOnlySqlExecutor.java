package io.github.claw4j.capability.builtin.sales;

import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 只读约束的第二道：把查询关进一个数据库层面的只读事务里执行。
 *
 * <p><b>为什么还要第二道</b>：{@link SalesSqlGuard} 是字符串校验，而 PostgreSQL 有可写 CTE
 * （{@code with x as (insert ... returning *) select * from x} 是一条语句、且以 with 开头）。
 * 字符串校验能从常见的写操作里拦下绝大多数，但"拦下绝大多数"不是保证。
 * 这一道是<b>数据库自己拒绝</b>：校验漏了，写操作在这里照样失败，而不是真的落库。
 *
 * <p><b>为什么是只读事务而不是配一个只读数据库用户</b>：
 * 配只读用户要引入第二套连接凭据（迁移用 owner、运行用 reader），
 * 而这套凭据在 Testcontainers 里还得现场造一遍角色与授权，
 * 复杂度换来的保护和"事务本身是只读的"完全等价。
 * 真要上生产，只读用户仍然该配——那是纵深防御的第三层，与这一层不冲突。
 *
 * <p><b>只读是怎么生效的</b>：{@code TransactionTemplate.setReadOnly(true)} 由 Spring 翻译成
 * {@code Connection.setReadOnly(true)}，PostgreSQL 驱动再把它翻译成事务级只读标记。
 * 这里<b>没有</b>额外发一条 {@code set transaction read only}——试过，
 * 变异检验显示去掉那条语句测试照样绿（驱动已经做了），留着就是一段测不出作用的代码。
 *
 * <p>顺带设了 {@code statement_timeout}：模型写出来的 SQL 可能是没写 join 条件的笛卡尔积，
 * 它语法合法、语义荒谬，跑得完但会拖住整个连接池。超时让它自己撞墙，
 * 而不是靠人工发现"库怎么慢了"。
 */
public class JdbcReadOnlySqlExecutor implements ReadOnlySqlExecutor {

    /** 默认给模型生成的 SQL 多少时间。它常常是"能跑完但没必要跑完"的那种查询。 */
    public static final Duration DEFAULT_STATEMENT_TIMEOUT = Duration.ofSeconds(5);

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final int maxRows;

    private final Duration statementTimeout;

    public JdbcReadOnlySqlExecutor(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this(jdbc, transactionManager, SalesSqlGuard.MAX_ROWS, DEFAULT_STATEMENT_TIMEOUT);
    }

    public JdbcReadOnlySqlExecutor(JdbcClient jdbc, PlatformTransactionManager transactionManager,
                                   int maxRows, Duration statementTimeout) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(transactionManager, "transactionManager");
        Objects.requireNonNull(statementTimeout, "statementTimeout");
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows 必须为正数，实际是 " + maxRows);
        }
        if (statementTimeout.isZero() || statementTimeout.isNegative()) {
            throw new IllegalArgumentException("statementTimeout 必须为正，实际是 " + statementTimeout);
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        // 这一行就是只读约束的全部来源。显式设在这里而不是靠调用方加
        // @Transactional(readOnly=true)：这条约束属于"执行 SQL"这件事本身，
        // 不该分散到每个调用点——漏掉一个调用点就等于开了一个口子。
        template.setReadOnly(true);
        this.transactions = template;
        this.maxRows = maxRows;
        this.statementTimeout = statementTimeout;
    }

    @Override
    public QueryResult execute(String sql) {
        Objects.requireNonNull(sql, "sql");
        // SET 必须和查询在同一条连接上发生，所以一起待在事务里——
        // 出了事务就是连接池里的另一条连接，set 的那些东西对它没有任何约束力。
        return transactions.execute(status -> {
            // 时长必须带引号：写成 set statement_timeout = 5s 是语法错误，
            // 而这条 SET 在每次查询前都要跑，拼错了的表现是"所有查询都失败"，
            // 一眼看过去很像数据库挂了。
            jdbc.sql("set local statement_timeout = '" + statementTimeout.toMillis() + "ms'").update();
            return fetch(SalesSqlGuard.withRowLimit(sql, maxRows + 1));
        });
    }

    /**
     * 多取一行用来判断是否被截断：只取 {@code maxRows} 行的话，
     * "刚好这么多行"和"被截断了"返回的行数相同，模型会以为那就是全部——
     * 于是它对着前 50 行报出一个"合计"，而那个数没有单位也没有前提。
     */
    private QueryResult fetch(String limitedSql) {
        ResultSetExtractor<QueryResult> extractor = resultSet -> {
            int columnCount = resultSet.getMetaData().getColumnCount();
            List<String> columns = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                columns.add(resultSet.getMetaData().getColumnLabel(i));
            }

            List<List<String>> rows = new ArrayList<>();
            while (rows.size() <= maxRows && resultSet.next()) {
                List<String> row = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    row.add(render(resultSet.getObject(i)));
                }
                rows.add(row);
            }

            boolean truncated = rows.size() > maxRows;
            return new QueryResult(List.copyOf(columns),
                    truncated ? List.copyOf(rows.subList(0, maxRows)) : List.copyOf(rows),
                    truncated);
        };
        return jdbc.sql(limitedSql).query(extractor);
    }

    /**
     * SQL 的 NULL 与字符串 "null" 在提示词里必须能区分，否则模型会把空值当成真值念出来。
     */
    private static String render(Object value) {
        return value == null ? "NULL" : value.toString();
    }
}
