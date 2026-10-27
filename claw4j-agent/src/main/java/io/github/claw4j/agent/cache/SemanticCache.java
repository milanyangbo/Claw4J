package io.github.claw4j.agent.cache;

import java.util.Optional;

/**
 * 语义缓存：同一个问题（或者换个说法问的同一个问题）第二次直接拿上次的回答。
 *
 * <p><b>接口只做"找最像的一条"和"存一条"，判定留在一层之上</b>：命中要同时满足
 * "同一个授权范围 + 相似度过阈值 + 没过期"，前两条里只有"授权范围"是存储的入参，
 * 相似度与过期的判定写在 {@link CacheAdvisor} 里。分成这样是为了让"什么样的相似度才算命中"
 * 能被测——那一层的输入是分数，不需要一个真库。
 *
 * <p><b>为什么缓存的是"一轮问答"而不是"一次工具调用"</b>：工具结果 5 分钟的窗口内
 * 才勉强安全，而整轮问答的价值在于"同一个人十分钟后再问一遍"。缓工具结果还要处理
 * 参数序列化与表级授权，收益与复杂度不成比例。
 */
public interface SemanticCache {

    /**
     * 找最相似的一条。相似度是余弦相似（1 = 完全相同，0 = 无关），
     * **不做阈值判断**——0.93 还是 0.5 算命中是调用方的策略。
     *
     * @return 没有候选（缓存是空的、或这个权限指纹下没有候选）时为空
     */
    Optional<Candidate> nearest(String fingerprint, String question, float[] embedding);

    /**
     * 不分权限范围，找全局最相似的一条。
     *
     * <p>只有一处用得上：本范围里没有候选时，用它区分"缓存里真的没有"与"有，但在别人的
     * 权限范围里"。两个用户问同一句话、一个命中一个 miss，日志上如果都写 no-candidate，
     * 看起来就像缓存压根没工作。
     *
     * <p><b>它只用于诊断</b>：返回的候选绝不参与"能不能命中"的判定——
     * 那样就等于绕过了授权。这一条是安全边界，要有用例守着。
     */
    Optional<Candidate> nearestAnyFingerprint(float[] embedding);

    /**
     * 存一条。同一个 {@code fingerprint + question} 已经有了就覆盖——
     * 覆盖而不是新增，是为了让"同一个问题问一百遍"不会在表里躺一百行。
     */
    void store(String fingerprint, String question, float[] embedding, String answer);

    /** 删掉已过期的，返回删了几条。TTL 只是行上的一个时间戳，没有这一步就一行都不删。 */
    int deleteExpired();

    /** 一条候选。{@code question} 要带回来：判定还需要看它里面的时间词。 */
    record Candidate(String question, String answer, double similarity) {
    }
}
