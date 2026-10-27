package io.github.claw4j.agent;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.tool.toolsearch.ToolIndex;

/**
 * 工具检索这一档怎么落到工具循环上。
 *
 * <p><b>它是替换工具循环，不是加一个 Advisor</b>：{@code ToolSearchToolCallingAdvisor}
 * 继承 {@code ToolCallingAdvisor}，本身就是一个完整的工具循环。两个都装上等于两个循环，
 * 而模型只会看见其中一个——另一个的预算、鉴权落点全是摆设。所以这里只产出**一个** builder。
 *
 * <p><b>为什么自己写 systemMessageSuffix</b>：不设时它读的是
 * {@code spring-ai-tool-search-tool} 里那份 {@code DEFAULT_SYSTEM_PROMPT_SUFFIX.md}——一句话的英文。
 * 那个模块里还有一份更详细的 {@code DEFAULT_SYSTEM_PROMPT_SUFFIX_LARGE.md}（讲清"先检索、再调用"两步），
 * 但 Builder 只读不带后缀的那份，永远用不到大的。我们的提示词是中文，直接给一份中文的，
 * 顺带把两步动作说明白——只把工具藏起来不解释，模型会以为当前清单就是全部能力。
 * （这两份文件在 <b>tool</b> 那个 jar 的根目录，不在 advisor 的 jar 里，别去错地方找。）
 *
 * <p><b>会话 id 的键名用默认值就够</b>：默认是 {@code ChatMemory.CONVERSATION_ID}
 * （{@code chat_memory_conversation_id}），正是 {@code ChatClientAgentPort} 每一轮塞进去的那个
 * （会话记忆的 Advisor 用的也是同一个键）。这不是巧合对上，是两边都以 ChatMemory 的约定为准。
 * 这个键缺失时它不会"退化成全塞"，而是直接抛异常——所以只有经过 AgentPort 的调用才有这个能力。
 */
final class ToolSearch {

    /**
     * 框架注册的检索工具名，{@code ToolSearchTool#toolSearchTool} 上 {@code @Tool(name=...)} 写的。
     *
     * <p>写死是有代价的：框架改名这里不会编译失败。兜住它的是"检索开着时模型只看得见这一个工具"
     * 那条用例——名字变了它先红。权限登记表也按这个名登记，两处都用这一个常量。
     */
    static final String TOOL_NAME = "toolSearchTool";

    /**
     * 接在系统提示词后面的那段话。
     *
     * <p>必须说清"先检索再调用"这个两步动作：只把工具藏起来而不解释，模型会以为当前清单就是全部能力，
     * 然后拿一个不合适的工具硬凑，或者直接说做不到。措辞里带"关键词"是因为
     * {@code RegexToolIndex} 就是按关键词在工具名与描述里匹配的。
     */
    static final String SYSTEM_SUFFIX = "\n\n"
            + "你手上没有列出全部能力。当前这些工具做不了这件事时，"
            + "先调用 toolSearchTool，用一句话描述你需要的能力（带上关键词），"
            + "它会把匹配到的工具交给你，下一轮就能直接调用。\n";

    private ToolSearch() {
    }

    /**
     * 产出这一通对话唯一的工具循环。
     *
     * <p>预算只有一个落点：两条分支都从这里过一遍。分开写的话，"检索开着时预算是 40/150"
     * 这种差异不会有任何提示——它看起来只是另一个 builder。
     *
     * @param toolCount 容器里一共有几个工具，决定全塞还是检索
     * @param toolIndex 检索索引；为 null 时退回全塞（没有索引就没得检索，不是错误）
     */
    static ToolCallingAdvisor.Builder<?> toolLoop(ToolSearchProperties properties,
                                                  int toolCount,
                                                  ToolIndex toolIndex,
                                                  ObservationRegistry observations) {
        ToolCallingAdvisor.Builder<?> builder =
                toolIndex != null && properties.searchInsteadOfStuffing(toolCount)
                        ? searchBuilder(properties, toolIndex)
                        : ToolCallingAdvisor.builder();
        builder.toolCallingManager(ToolCallBudget.toolCallingManager(observations));
        return builder;
    }

    private static ToolCallingAdvisor.Builder<?> searchBuilder(ToolSearchProperties properties, ToolIndex toolIndex) {
        return ToolSearchToolCallingAdvisor.builder()
                .toolIndex(toolIndex)
                .maxResults(properties.maxResults())
                .systemMessageSuffix(SYSTEM_SUFFIX);
    }
}
