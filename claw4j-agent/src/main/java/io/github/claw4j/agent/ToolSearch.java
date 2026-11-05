package io.github.claw4j.agent;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.tool.toolsearch.ToolIndex;

import java.util.Objects;

/**
 * 这一通对话的工具循环：<b>只有一种，就是渐进式披露</b>。
 *
 * <p>{@code ToolSearchToolCallingAdvisor} 继承 {@code ToolCallingAdvisor}，本身就是完整的
 * 工具循环（自己递归调链上后面的 Advisor、自己管预算、自己判要不要再问一次模型），
 * 只是每一轮交给模型的工具清单从"全部"换成了"检索工具 + 已经查到的"。
 * 所以这里不产出两个 builder：两个循环装在一起时模型只看得见其中一个，
 * 另一个的预算与鉴权落点全是摆设。
 *
 * <p><b>没有"工具少就全塞"的分支</b>。留那条分支要付的代价不是多几行代码，是模型看到的行为
 * 会随工具数量改变：同一句问话，工具从 20 个涨到 21 个的那个版本开始，模型面对的就是另一套规则。
 * 代价写在明处——工具集很小时也要先检索一次，多一轮往返；换来的是"模型永远看得见找工具这个动作"
 * 这一条恒定行为，以及只有一套要验的东西。
 *
 * <p><b>为什么自己写 systemMessageSuffix</b>：不设时它读的是
 * {@code spring-ai-tool-search-tool} 里那份 {@code DEFAULT_SYSTEM_PROMPT_SUFFIX.md}——一句话的英文。
 * 那个模块里还有一份更详细的 {@code DEFAULT_SYSTEM_PROMPT_SUFFIX_LARGE.md}（讲清"先检索、再调用"两步），
 * 但 Builder 只读不带后缀的那份，永远用不到大的。我们的提示词是中文，直接给一份中文的，
 * 顺带把两步动作说明白——只把工具藏起来不解释，模型会以为当前清单就是全部能力。
 * （这两份文件在 <b>tool</b> 那个 jar 的根目录，不在 advisor 的 jar 里，别去错地方找。）
 *
 * <p><b>它是"替换循环"而不是"多挂一个 Advisor"</b>：{@code DefaultChatClient} 在链上出现
 * 第二个说明自己是工具循环的 Advisor 时直接抛 {@code IllegalStateException}，
 * 所以也没有走 starter——那个自动配置会自己塞一个进来。
 *
 * <p><b>会话 id 的键名用默认值就够</b>：默认是 {@code ChatMemory.CONVERSATION_ID}
 * （{@code chat_memory_conversation_id}），正是 {@code ChatClientAgentPort} 每一轮塞进去的那个
 * （会话记忆的 Advisor 用的也是同一个键）。这不是巧合对上，是两边都以 ChatMemory 的约定为准。
 * 这个键缺失时它不会"退化成全塞"，而是直接抛异常——所以只有经过 AgentPort 的调用才有这个能力，
 * 自己另建 ChatClient 的人（子 Agent）必须自己把这个键补上。
 */
public final class ToolSearch {

    /**
     * 框架注册的检索工具名，{@code ToolSearchTool#toolSearchTool} 上 {@code @Tool(name=...)} 写的。
     *
     * <p>写死是有代价的：框架改名这里不会编译失败。兜住它的是"模型只看得见这一个工具"
     * 那条用例——名字变了它先红。权限登记表也按这个名登记，两处都用这一个常量。
     */
    static final String TOOL_NAME = "toolSearchTool";

    /**
     * 接在系统提示词后面的那段话。
     *
     * <p><b>"看不到工具不等于没有这个能力"这一句是必需的，不是修辞</b>：把工具藏起来之后，
     * 模型看到的清单里常常只剩一个检索工具。真模型实测过——不写这句，它会直接回答
     * "我这边目前没有直接查询业务数据的工具"，然后结束，一次检索都不发。
     * 而系统提示词里"不确定时向用户提问"那条指令会把这件事放大：它先问口径，问完就把
     * "没有工具"一起说出来了。所以这里还要说清"确认口径"与"先检索"不冲突。
     *
     * <p>必须说清"先检索再调用"这个两步动作：只把工具藏起来而不解释，模型会以为当前清单就是全部能力，
     * 然后拿一个不合适的工具硬凑，或者直接说做不到。
     *
     * <p><b>这里不再要求"用空格分隔的关键词"</b>。那句话是 {@code RegexToolIndex} 只按空白与标点
     * 切词的补丁：中文整句进去是一个 token，当子串匹配等于什么都不匹配，于是只能在提示词里
     * 教模型自己先分词。代价是模型每次检索前多做一次本该我们做的活，一次没照做就空手而归——
     * 而下面那句"看不到合适的工具不代表没有这个能力"又不让它说没有，它只能硬凑一个工具。
     *
     * <p>索引换成 {@link ChineseToolIndex} 之后整句本来就能命中（相邻两字一组），
     * 所以这里改回"用一句自然语言说清你要什么"——**两处必须一起改**：只改索引不改提示词，
     * 等于留着一段教模型绕一个已经不存在的限制的话，比不改更糟。
     *
     * <p><b>"看不到工具不等于没有这个能力"这一句仍然是必需的，不是修辞</b>：把工具藏起来之后，
     * 模型看到的清单里常常只剩一个检索工具。真模型实测过——不写这句，它会直接回答
     * "我这边目前没有直接查询业务数据的工具"，然后结束，一次检索都不发。
     * 而系统提示词里"不确定时向用户提问"那条指令会把这件事放大：它先问口径，问完就把
     * "没有工具"一起说出来了。所以这里还要说清"确认口径"与"先检索"不冲突。
     *
     * <p>必须说清"先检索再调用"这个两步动作：只把工具藏起来而不解释，模型会以为当前清单就是全部能力，
     * 然后拿一个不合适的工具硬凑，或者直接说做不到。
     */
    static final String SYSTEM_SUFFIX = "\n\n"
            + "**你现在只看得见一个叫 toolSearchTool 的「找工具」工具，其余能力按需检索。**\n"
            + "看不到合适的工具**不代表没有这个能力**，不要因此回答\"我没有工具\"或\"做不到\"——\n"
            + "先调用 toolSearchTool，它会把匹配到的工具交给你，下一轮就能直接调用。\n"
            + "遇到查数、统计、比对这类业务问题时，先检索一次；需要向用户确认口径时，\n"
            + "也可以先检索（两件事不冲突，先看清手上有什么再问更准）。\n"
            + "检索时**用一句自然语言说清你要什么能力**（例如「帮我找之前聊过的内容」「查上周的销售成交额」），"
            + "不用自己拆关键词——中文和英文它都认。\n";

    private ToolSearch() {
    }

    /**
     * 产出这一通对话唯一的工具循环。
     *
     * <p>预算只有一个落点：从这里过一遍 {@link ToolCallBudget}。分开写的话，
     * "检索时预算是 40/150"这种差异不会有任何提示——它看起来只是另一个 builder。
     *
     * @param toolIndex 检索索引，必填。没有索引时 {@code build()} 会直接报错，
     *                  而不是悄悄退化成全塞——退化的那一路正是这里删掉的东西
     */
    public static ToolSearchToolCallingAdvisor.Builder<?> toolLoop(ToolSearchProperties properties,
                                                                   ToolIndex toolIndex,
                                                                   ObservationRegistry observations) {
        return ToolSearchToolCallingAdvisor.builder()
                .toolIndex(Objects.requireNonNull(toolIndex, "toolIndex"))
                .maxResults(properties.maxResults())
                .systemMessageSuffix(SYSTEM_SUFFIX)
                .toolCallingManager(ToolCallBudget.toolCallingManager(observations));
    }
}
