package io.github.claw4j.tools.sales;

import java.util.List;

/**
 * 一次只读查询的结果：列名、行、以及"是不是被截断了"。
 *
 * <p>行里的值一律是字符串：它接下来要进提示词给模型看，不是要参与运算。
 * 带着 {@code BigDecimal} / {@code LocalDate} 往下传，格式化责任就会散在好几处，
 * 而"金额在提示词里长什么样"这种事只该有一个地方说了算。
 *
 * @param truncated 结果行数超过了上限。为 true 时 {@code rows} 只有前 N 行——
 *                  模型必须知道这一点，否则它会把"前 N 行的合计"当成"全部合计"。
 */
public record QueryResult(List<String> columns, List<List<String>> rows, boolean truncated) {

    public static QueryResult empty() {
        return new QueryResult(List.of(), List.of(), false);
    }

    public int rowCount() {
        return rows.size();
    }
}
