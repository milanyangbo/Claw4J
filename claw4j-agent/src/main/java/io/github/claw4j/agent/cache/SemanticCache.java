package io.github.claw4j.agent.cache;

import java.util.Optional;

/**
 * 语义缓存：同一个问题（或者换个说法问的同一个问题）第二次直接拿上次的回答。
 *
 * <p><b>接口只做"找最像的一条"和"存一条"，判定留在一层之上</b>：命中要同时满足
 * "同一个上下文 + 相似度过阈值 + 没过期"，只有上下文是存储的入参，
 * 相似度与过期的判定写在 {@link CacheAdvisor} 里。分成这样是为了让"什么样的相似度才算命中"
 * 能被测——那一层的输入是分数，不需要一个真库。
 *
 * <p><b>上下文是两段拼起来的</b>：{@code fingerprint}（权限范围）与 {@code entities}
 * （问题里的时间词与数字）。两段合起来才相当于框架 {@code SemanticCache} 接口里的
 * 那个 {@code contextHash}——框架自己填的是系统提示词，我们填的是"这份权限下、
 * 这个时间窗的问题"。分成两段而不是哈希成一段，是为了让 miss 的原因分得清：
 * 同权限但时间窗不同（不同实体）和同问题但权限不同，是两种完全不同的现象。
 *
 * <p><b>为什么缓存的是"一轮问答"而不是"一次工具调用"</b>：工具结果 5 分钟的窗口内
 * 才勉强安全，而整轮问答的价值在于"同一个人十分钟后再问一遍"。缓工具结果还要处理
 * 参数序列化与表级授权，收益与复杂度不成比例。
 */
public interface SemanticCache {

    /**
     * 在同一个上下文里找最相似的一条。相似度是余弦相似（1 = 完全相同，0 = 无关），
     * **不做阈值判断**——0.93 还是 0.5 算命中是调用方的策略。
     *
     * @return 这个上下文里没有候选时为空
     */
    Optional<Candidate> nearest(String fingerprint, String entities, float[] embedding);

    /**
     * 同一个权限范围、**不限实体**，找最相似的一条。
     *
     * <p>只有一处用得上：本上下文里没有候选时，用它区分"缓存里真的没有"与
     * "有同权限的相似问题，但时间窗或数字不同"。后者是实测里最要紧的一种 miss——
     * 它说明这问题问过一次，只是问的不是同一段时间。
     *
     * <p><b>它只用于诊断</b>：返回的候选绝不参与"能不能命中"的判定。
     */
    Optional<Candidate> nearestInFingerprint(String fingerprint, float[] embedding);

    /**
     * 不分权限、不分实体，找全局最相似的一条。
     *
     * <p>同样只用于诊断：两个用户问同一句话、一个命中一个 miss，日志上如果都写
     * no-candidate，看起来就像缓存压根没工作。
     *
     * <p><b>返回的候选绝不参与命中判定</b>——那样等于绕过了授权。这一条是安全边界，
     * 要有用例守着。
     */
    Optional<Candidate> nearestAnyFingerprint(float[] embedding);

    /**
     * 存一条。同一个 {@code fingerprint + entities + question} 已经有了就覆盖——
     * 覆盖而不是新增，是为了让"同一个问题问一百遍"不会在表里躺一百行。
     */
    void store(String fingerprint, String entities, String question, float[] embedding, String answer);

    /** 删掉已过期的，返回删了几条。TTL 只是行上的一个时间戳，没有这一步就一行都不删。 */
    int deleteExpired();

    /** 一条候选。{@code question} 要带回来：日志里靠它说明"到底是哪一句被当成了相似"。 */
    record Candidate(String question, String answer, double similarity) {
    }
}
