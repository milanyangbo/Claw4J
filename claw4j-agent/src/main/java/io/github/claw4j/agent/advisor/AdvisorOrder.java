package io.github.claw4j.agent.advisor;

import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

/**
 * 整条 Advisor 链的顺序：谁在谁里层，只有这一份。
 *
 * <p>在这之前它散在七个类里，用了三种基准（{@code HIGHEST_PRECEDENCE}、
 * {@code DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER}、以及互相之间的 {@code +1}）——每一次「脱敏到底在
 * 循环里面还是外面」「缓存为什么不进哈希键」都要读七个文件、比较七个数字才答得出来，而答错的表现
 * 不是报错：是某条防线在流式入口上静默失效。
 *
 * <p>order <b>越小越靠外</b>（Spring AI 的 {@code reOrder()} 按升序排，先执行的是 before，后执行的
 * 是 after）。所以下面这张表从外到内，也就是「一次回答的旅行的顺序」：
 *
 * <pre>
 * 外 ┌ GUARD             入站拦截：命中就整轮拒答，连 token 都不花；记忆在它里层，看不到这一轮
 *    │ SESSION_MEMORY    框架的会话记忆：before 读历史、after 写这一轮
 *    │ AGENT_CONTEXT     拼系统提示词：人设 + 口径 + 现在的时间（必须在缓存之前，见该类注释）
 *    │ PRINCIPAL_MEMORY  长期记忆：把库的 AutoMemoryToolsAdvisor 按人分发
 *    │ PRINCIPAL_DREAM   记忆整理：带外跑，before 什么都不改
 *    │ PII_REDACTION     出站脱敏：在工具循环之外，整通对话只做一次
 *    │ SEMANTIC_CACHE    语义缓存：把整份系统提示词当哈希键，所以必须在 AGENT_CONTEXT 之后
 *    │ TOOL_LOOP         工具循环（渐进式披露）：它自己会递归调链上后面的一切
 * 内 └ …                 每一轮真正发给模型的东西、{@code SimpleLoggerAdvisor}（框架默认 0 = 最内层）
 * </pre>
 *
 * <p><b>这些数字要改就改在这里</b>，不要在装配类里另写一个 {@code +1}：两个来源之间没有编译期联系，
 * 挪了其中一个，另一个仍然「看起来对」。
 *
 * <p>每轮另挂的那条墙钟（{@code InterruptAdvisor}）不在这张表里：它的截止时刻每轮现算，所以是
 * per-request 的 advisor，order 用库的默认值（工具循环内层），见 {@code ChatClientAgentPort}。
 *
 * <p>名字不叫 {@code AdvisorChain}：那个名字是框架的链接口（{@code
 * org.springframework.ai.chat.client.advisor.api.AdvisorChain}），而
 * {@code PrincipalMemoryAdvisor} 这类分发壳的签名里就带着它，同名会逼出全限定名。
 */
public final class AdvisorOrder {

    /**
     * 入站拦截（框架的 {@code SafeGuardAdvisor}）。
     *
     * <p>必须最外：命中时框架直接返回一句固定答复、不调后续 advisor，于是这次注入连会话历史都不进，
     * 一个 token 都不花。排在记忆里层的话，只要历史里有一轮出现过某个词，之后每一轮都会被同一个词
     * 拦下——会话就此锁死，而用户看到的只是「从某句话开始，怎么问都不理我了」。
     *
     * <p>代价是历史里已经存在的词不再被拦。这是有意的：历史只可能装着当时被放行的内容。要管住历史，
     * 该在写入时管，不是在读出来时管。
     */
    public static final int GUARD = Ordered.HIGHEST_PRECEDENCE;

    /**
     * 会话记忆（框架的 {@link org.springframework.ai.session.advisor.SessionMemoryAdvisor}）。
     *
     * <p>在 guard 里层、其余一切外层，于是 {@code before} 它第二早、{@code after} 它<b>最后</b>
     * 收尾（收尾按 order 降序跑）。这一条是要害：它最后收尾，写进会话表的才是
     * {@link #PII_REDACTION} 已经打过码的文本。反过来写，出站是干净的、会话表里躺的是明文——
     * 那才是更持久的一份，而且没人会去看。
     */
    public static final int SESSION_MEMORY = GUARD + 1;

    /**
     * 系统提示词拼装（{@code AgentContextAdvisor}）。
     *
     * <p>必须在语义缓存之前：缓存的上下文键是 {@code SHA-256(整份系统提示词)}。排在它后面的话，
     * 哈希的是一份还没拼上数据范围与时间的提示词，权限隔离与过期判定同时失效。
     */
    public static final int AGENT_CONTEXT = SESSION_MEMORY + 1;

    /**
     * 长期记忆（{@code PrincipalMemoryAdvisor}）。
     *
     * <p>在提示词拼装之后：记忆块要拼在最终系统提示的尾部，拼在之前会被下一次拼装覆盖掉。
     */
    public static final int PRINCIPAL_MEMORY = AGENT_CONTEXT + 1;

    /**
     * 记忆整理（{@code PrincipalDreamAdvisor}）。
     *
     * <p>紧跟长期记忆：它分发的是同一批按人建的 advisor，整理发生在带外，before 只做转发。
     */
    public static final int PRINCIPAL_DREAM = PRINCIPAL_MEMORY + 1;

    /**
     * 出站脱敏（{@code PiiRedactionAdvisor}）。
     *
     * <p>比工具循环小，所以在它外面：整通对话只在出站前打一次码。放进循环里的话，工具结果与模型的
     * 中间轮次也会被打码——模型要靠原始值判断「这个号码是哪一单的」，打掉了它就没得判。
     *
     * <p><b>它的下限由会话记忆定</b>：{@code after} 按 order 降序跑，order 越大越先收尾。脱敏必须比
     * {@link #SESSION_MEMORY} 大，才能抢在记忆写库之前把回答打上码——否则「出站脱敏了」是真的，
     * 会话表里躺着的仍是明文，而且是更持久的那一份。
     *
     * <p>取值仍是框架的「聊天记忆档」：这个数字此前是逐位定下来的，换基准等于换顺序。
     */
    public static final int PII_REDACTION = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;

    /**
     * 语义缓存（框架的 {@code SemanticCacheAdvisor}）。
     *
     * <p>在脱敏里层、工具循环外层，三件事同时成立：
     *
     * <ul>
     *   <li>比 {@link #SESSION_MEMORY} 大：记忆的 before 先把这条用户消息写进会话，命中缓存时
     *       记忆的 after 照样会把"（同上次）"写回去，聊天记录是连续的——少这一轮最难解释。
     *   <li>比 {@link #TOOL_LOOP} 小：整通对话只查一次，不会在每一轮工具迭代上重复查。
     *   <li>比 {@link #PII_REDACTION} 大：命中时返回的是一份旧答案，出站仍然要过脱敏。
     * </ul>
     *
     * <p><b>必须显式传</b>：框架给这个 advisor 的默认 order 正好落在 {@link #PII_REDACTION}
     * 那一格（{@code -2147483448}），不传的话两者同 order，先后由 bean 名字决定——这种"顺序靠巧合"
     * 的链最难查。
     */
    public static final int SEMANTIC_CACHE = PII_REDACTION + 50;

    /**
     * 工具循环（{@code ToolSearchToolCallingAdvisor}）。
     *
     * <p>用框架的值而不是自己定：它是这条链的分界线——外面的 advisor 一通对话只跑一次，里面的
     * 每一轮模型请求都会重跑一遍。这个数字因此不是「第几个」，是「哪些 advisor 被循环复读」。
     */
    public static final int TOOL_LOOP = ToolCallingAdvisor.DEFAULT_ORDER;

    private AdvisorOrder() {
    }
}
