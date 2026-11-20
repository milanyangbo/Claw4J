package io.github.claw4j.agent.rag;

/**
 * 改写的保护层：哪些问题不该改写、改成什么样算安全。
 *
 * <p>抽成接口而不是把正则放进配置：配置里写错正则时，保护会<b>静默失效</b>——改写把
 * 「万分之五」改成「千分之几」，没有任何报错，只是某天答案开始不准。而运维也不该被要求
 * 懂正则语法。要换领域，注册自己的 {@code RewriteGuard} bean 就行，有编译期检查也有单测。
 *
 * <p>两个方法对应两道闸，缺一不可：
 * <ul>
 *   <li>{@link #shouldSkip} 是<b>事前</b>的：知道这类问题改写了会坏事，干脆不调模型
 *   <li>{@link #keepsAll} 是<b>事后</b>的：模型已经把问题改掉了，检查它有没有改坏
 * </ul>
 *
 * <p>只有事后闸也能工作（多花一次调用），只有事前闸则防不住模型抽风。
 */
public interface RewriteGuard {

    /**
     * 这个问题要不要跳过改写。
     *
     * @param question 用户原问题（已 strip，非空）
     * @return true = 不改写，直接拿原问题去检索
     */
    boolean shouldSkip(String question);

    /**
     * 改写结果有没有把原问题里必须保留的东西带过来。
     *
     * @param question  用户原问题
     * @param rewritten 模型给出的改写（已清洗）
     * @return false = 不接受这次改写，回落原问题
     */
    boolean keepsAll(String question, String rewritten);
}
