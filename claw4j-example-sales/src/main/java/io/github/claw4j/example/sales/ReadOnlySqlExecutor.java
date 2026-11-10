package io.github.claw4j.example.sales;

/**
 * 数据源接缝：把执行一条只读 SQL 从怎么连库、怎么开事务里摘出来。
 *
 * <p>它是 05 号票新增的第三条接缝，另两条是 {@code AgentPort} 与 {@code @Tool} 方法。存在的理由：
 * Agent 侧的循环测试（自纠错、调用上限）要能在没有数据库的地方跑，而真实实现必须有库。
 * 中间隔着这一层，两边各自测各自的：循环的行为不依赖 Docker，SQL 的正确性不依赖模型。
 *
 * <p>契约只有一条：传进来的 SQL 已经过了 {@link SalesSqlGuard}。实现不需要再校验一遍，
 * 但必须保证自己不执行写操作，校验是字符串层面的、可能漏，数据库层面的拒绝由实现负责。
 */
public interface ReadOnlySqlExecutor {

    /**
     * 执行一条只读查询。
     *
     * @param sql 已过 {@link SalesSqlGuard} 的查询语句
     * @return 列名、行与被截断标记
     * @throws org.springframework.dao.DataAccessException 数据库拒绝了这条语句；抛出去而不是返回空
     *     结果，是为了让调用方能区分查到了 0 行与根本没查成功，这两件事对模型的下一步完全不同
     */
    QueryResult execute(String sql);
}
