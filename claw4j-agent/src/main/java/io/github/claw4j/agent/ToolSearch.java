package io.github.claw4j.agent;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.tool.toolsearch.ToolIndex;

import java.util.Objects;

/**
 * 这一通对话的工具循环：只有一种，即渐进式披露。
 *
 * <p>{@code ToolSearchToolCallingAdvisor} 继承 {@code ToolCallingAdvisor}，本身就是一个完整的
 * 工具循环（自己递归调链上后面的 Advisor、自己管预算、自己判要不要再问一次模型），只是每轮交给
 * 模型的工具清单从全部换成「检索工具 + 已查到的」。所以这里不产出两个 builder：两个循环装在一起
 * 时模型只看得见其中一个，另一个的预算与鉴权落点都是摆设。
 *
 * <p>没有「工具少就全塞」的分支。留它的代价不是多几行代码，而是模型看到的行为随工具数量改变：
 * 同一句问话，在工具从 20 个涨到 21 个的那个版本里就换了一套规则。代价写在明处——工具集很小时
 * 也要先检索一次，多一轮往返；换来的是「模型永远看得见找工具这个动作」这一条恒定行为，
 * 以及只有一套要验的东西。
 *
 * <p>用替换而不是多挂一个 Advisor：{@code DefaultChatClient} 在链上出现第二个自称工具循环的
 * Advisor 时直接抛 {@code IllegalStateException}，所以也没有走 starter
 * （{@code ToolSearchAdvisorAutoConfiguration} 会自己塞一个进来）。
 *
 * <p>会话 id 的键名用默认值：{@code ChatMemory.CONVERSATION_ID}
 * （{@code chat_memory_conversation_id}），正是 {@code ChatClientAgentPort} 每轮塞进去的那个键，
 * 会话记忆的 Advisor 用的也是它。键缺失时不退化成全塞，而是抛
 * {@code IllegalArgumentException}（Spring AI {@code ToolSearchToolCallingAdvisor}:326），
 * 所以只有经过 AgentPort 的调用才有这个能力，自建 ChatClient 的子 Agent 必须自己补上。
 */
public final class ToolSearch {

    /**
     * 框架注册的检索工具名，即 Spring AI {@code ToolSearchTool} 上 {@code @Tool(name = ...)} 的值
     * （{@code ToolSearchTool}:42）。
     *
     * <p>写死是有代价的：框架改名这里不会编译失败。兜住它的是"模型只看得见这一个工具"
     * 那条用例——名字变了它先红。权限登记表也按这个名登记，两处共用这一个常量。
     */
    static final String TOOL_NAME = "toolSearchTool";

    /**
     * 接在系统提示词后面的那段话。
     *
     * <p>自己写而不用框架默认值：Builder 只读 {@code DEFAULT_SYSTEM_PROMPT_SUFFIX.md}（一句英文），
     * 同一目录下更详细的 {@code _LARGE} 版永远用不到。这里的提示词是中文，顺便把两步动作写明。
     *
     * <p>"看不到工具不等于没有这个能力"这一句是必需的，不是修辞：工具被藏起来之后，模型看到的
     * 清单常常只剩一个检索工具，实测不写这句它会直接回答"我这边目前没有直接查询业务数据的工具"
     * 然后结束，一次检索都不发。系统提示词里"不确定时向用户提问"还会放大这件事——它先问口径，
     * 问完就把"没有工具"一起说了，所以还要说明确认口径与先检索不冲突。同理要说清"先检索、再调用"：
     * 只藏工具不解释，模型会拿一个不合适的工具硬凑，或者直接说做不到。
     *
     * <p>索引用 {@link ChineseToolIndex}（相邻两字一组），整句本来就能命中，所以这里让模型用一句
     * 自然语言说清要什么能力，不要求它自己拆关键词。提示词与索引必须一起改：只改索引不改提示词，
     * 等于留一段教模型绕开不存在的限制的话。
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
     * 这一通对话唯一的工具循环，尚未 build。
     *
     * <p>预算只有一个落点：从这里过一遍 {@link ToolCallBudget}。分开写的话，
     * 「检索时预算是 40/150」这种差异不会有任何提示，它看起来只是另一个 builder。
     *
     * @param properties 检索配置，取 {@code maxResults}
     * @param toolIndex 检索索引，必填。没有索引时 {@code build()} 直接报错，而不悄悄退化成全塞
     * @param observations 观测注册表，透传给工具调用 manager
     * @param toolCallLimit 整轮工具调用上限，见 {@link ToolCallBudget#toolCallingManager}
     * @return 配好索引、候选数、提示词与熔断的 builder
     */
    public static ToolSearchToolCallingAdvisor.Builder<?> toolLoop(ToolSearchProperties properties,
                                                                   ToolIndex toolIndex,
                                                                   ObservationRegistry observations,
                                                                   int toolCallLimit) {
        return ToolSearchToolCallingAdvisor.builder()
                .toolIndex(Objects.requireNonNull(toolIndex, "toolIndex"))
                .maxResults(properties.maxResults())
                .systemMessageSuffix(SYSTEM_SUFFIX)
                .toolCallingManager(ToolCallBudget.toolCallingManager(observations, toolCallLimit));
    }
}
