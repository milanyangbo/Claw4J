package io.github.claw4j.capability.builtin.sales;

/**
 * 数据源接缝：把"执行一条只读 SQL"从"怎么连库、怎么开事务"里摘出来。
 *
 * <p>它是 05 号票新增的第三条接缝（另两条是 {@code AgentPort} 与 {@code @Tool} 方法）。
 * 存在的直接理由：Agent 侧的循环测试（自纠错、调用上限）要能在<b>没有数据库</b>的地方跑，
 * 而 capability 模块的真实实现必须有库。中间隔着这一层，两边各自测各自的：
 * 循环的行为不依赖 Docker，SQL 的正确性不依赖模型。
 *
 * <p>契约只有一条：<b>传进来的 SQL 已经过了 {@link SalesSqlGuard}</b>。
 * 实现不需要再校验一遍，但必须保证自己不执行写操作——
 * 校验是字符串层面的，可能漏；数据库层面的拒绝由实现负责。
 */
public interface ReadOnlySqlExecutor {

    /**
     * 执行一条只读查询。
     *
     * @throws org.springframework.dao.DataAccessException 数据库拒绝了这条语句。
     *         抛出去而不是返回空结果，是为了让调用方能区分"查到了 0 行"
     *         与"根本没查成功"——这两件事对模型的下一步完全不同。
     */
    QueryResult execute(String sql);
}
