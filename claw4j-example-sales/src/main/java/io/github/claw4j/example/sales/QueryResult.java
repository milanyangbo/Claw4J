package io.github.claw4j.example.sales;

import java.util.List;

/**
 * 一次只读查询的结果：列名、行、以及是不是被截断了。
 *
 * <p>行里的值一律是字符串：它接下来要进提示词给模型看，不参与运算。带着 {@code BigDecimal}
 * 或 {@code LocalDate} 往下传，格式化责任就会散在好几处，而金额在提示词里长什么样只该有一个
 * 地方说了算。
 *
 * @param truncated 结果行数超过了上限；为 true 时 {@code rows} 只有前 N 行，模型必须知道这一点，
 *     否则它会把前 N 行的合计当成全部合计
 */
public record QueryResult(List<String> columns, List<List<String>> rows, boolean truncated) {

    /** 空结果：没有列、没有行、不算截断。 */
    public static QueryResult empty() {
        return new QueryResult(List.of(), List.of(), false);
    }

    /** 返回的行数，截断时是上限而不是真实总数。 */
    public int rowCount() {
        return rows.size();
    }
}
