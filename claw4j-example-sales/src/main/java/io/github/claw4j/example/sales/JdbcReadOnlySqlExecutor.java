package io.github.claw4j.example.sales;

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
 * <p>第一道 {@link SalesSqlGuard} 是字符串校验，而 PostgreSQL 有可写 CTE，
 * {@code with x as (insert ... returning *) select * from x} 是一条语句、且以 with 开头。
 * 字符串校验能拦下绝大多数写操作，但不是保证。这一道由数据库自己拒绝：校验漏了，
 * 写操作在这里照样失败，而不是真的落库。
 *
 * <p>用只读事务而不是配一个只读数据库用户：后者要引入第二套连接凭据（迁移用 owner、
 * 运行用 reader），在 Testcontainers 里还得现场造角色与授权，换来的保护与事务只读等价。
 * 上生产时只读用户仍该配，那是第三层。
 *
 * <p>只读的走向是 {@code TransactionTemplate.setReadOnly(true)} →
 * {@code Connection.setReadOnly(true)} → 驱动的事务级只读标记。这里没有再发一条
 * {@code set transaction read only}：驱动已经做了，那条语句去掉了测试照样绿。
 *
 * <p>顺带设 {@code statement_timeout}：模型写出来的 SQL 可能是没写 join 条件的笛卡尔积，
 * 语法合法、跑得完但会拖住整个连接池，超时让它自己撞墙。
 */
public class JdbcReadOnlySqlExecutor implements ReadOnlySqlExecutor {

    /** 默认给模型生成的 SQL 多少时间。它常常是"能跑完但没必要跑完"的那种查询。 */
    public static final Duration DEFAULT_STATEMENT_TIMEOUT = Duration.ofSeconds(5);

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final int maxRows;

    private final Duration statementTimeout;

    /**
     * 用 {@link SalesSqlGuard#MAX_ROWS} 与 5 秒超时。
     *
     * @param jdbc 查询用的客户端，连接来自同一个池
     * @param transactionManager 事务管理器，只读标记挂在它开的事务上
     */
    public JdbcReadOnlySqlExecutor(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this(jdbc, transactionManager, SalesSqlGuard.MAX_ROWS, DEFAULT_STATEMENT_TIMEOUT);
    }

    /**
     * 指定行数上限与语句超时。
     *
     * @param jdbc 查询用的客户端，连接来自同一个池
     * @param transactionManager 事务管理器，只读标记挂在它开的事务上
     * @param maxRows 返回给模型的最大行数；内部多取一行用来判断截断
     * @param statementTimeout 单条语句的超时
     */
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
     * 多取一行用来判断是否被截断：只取 {@code maxRows} 行的话，刚好这么多行与被截断返回的行数
     * 相同，模型会以为那就是全部，于是对着前 50 行报出一个没有前提的合计。
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
