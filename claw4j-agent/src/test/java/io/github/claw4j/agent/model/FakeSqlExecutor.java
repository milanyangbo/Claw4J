package io.github.claw4j.agent.model;

import io.github.claw4j.tools.sales.QueryResult;
import io.github.claw4j.tools.sales.ReadOnlySqlExecutor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据源接缝的替身。它让工具循环的行为（自纠错、调用上限）能在<b>没有数据库</b>的地方被测完整。
 *
 * <p>为什么值得单独一个夹具而不是在测试里写 lambda：断言要落在"这条 SQL 到底有没有被送到数据库"
 * 这种外部事实上，那就得有个东西记录每一次执行。记录列表放在测试里，每个测试都要重写一遍。
 *
 * <p>它刻意<b>不</b>做 SQL 解析：替身只按"含不含某个片段"决定成败。
 * 解析是 {@code SalesSqlGuard} 的事，替身再去实现一遍就等于把被测逻辑复制进了夹具——
 * 那样"校验改错了"和"夹具想错了"会同时变绿。
 */
public final class FakeSqlExecutor implements ReadOnlySqlExecutor {

    private final QueryResult success;

    private String failingFragment;

    private String failureMessage;

    private final List<String> executed = new ArrayList<>();

    private FakeSqlExecutor(QueryResult success) {
        this.success = success;
    }

    /** 总是返回同一份结果的替身。 */
    public static FakeSqlExecutor returning(QueryResult result) {
        return new FakeSqlExecutor(result);
    }

    /** 单行单列的简写：多数断言只关心那个数有没有出现在提示词里。 */
    public static FakeSqlExecutor returning(String column, String value) {
        return returning(new QueryResult(List.of(column), List.of(List.of(value)), false));
    }

    /** 含 {@code fragment} 的语句按"数据库报错"处理，其余走成功路径。这就是自纠错要造的场景。 */
    public FakeSqlExecutor failingWhen(String fragment, String message) {
        this.failingFragment = fragment;
        this.failureMessage = message;
        return this;
    }

    @Override
    public QueryResult execute(String sql) {
        executed.add(sql);
        if (failingFragment != null && sql.contains(failingFragment)) {
            throw new DataAccessResourceFailureException(failureMessage);
        }
        return success;
    }

    /** 真正被送到数据库的次数。断言"超限之后没有第四次"靠它。 */
    public int calls() {
        return executed.size();
    }

    public List<String> executed() {
        return List.copyOf(executed);
    }
}
