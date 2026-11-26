package io.github.claw4j.agent.toolaccess;

import io.github.claw4j.agent.turn.ToolCallBudget;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.index.lucene.LuceneToolIndex;
import org.springframework.ai.tool.toolsearch.index.vectorstore.VectorToolIndex;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.Objects;

/**
 * 这一通对话的工具循环：只有一种，即渐进式披露。
 *
 * <p>{@code ToolSearchToolCallingAdvisor} 继承 {@code ToolCallingAdvisor}，本身就是一个完整的
 * 工具循环（自己递归调链上后面的 Advisor、自己管预算、自己判要不要再问一次模型），只是每轮交给
 * 模型的工具清单从全部换成「检索工具 + 已查到的」。所以这里不产出两个 builder：两个循环装在一起
 * 时模型只看得见其中一个，另一个的预算与鉴权落点都是摆设。
 *
 * <p><b>2026-11-26 起有「工具少就全塞」这一支</b>（ADR-0021「领先 2」修订，票 06）：原来写的是
 * "行为不随工具数量变化"，代价估的是"工具少也得多花一轮检索"。实测（票 05）这一轮不是花在边缘
 * 场景，而是花在最常见的一类问法上——{@code examples 目录下有哪些 md} 这种话模型不去检索，
 * 直接答"我没有能列目录的工具"。现在按 {@link ToolSearchProperties.Mode} 三档：默认 {@code auto}
 * 在工具数不超过 {@code reveal-below} 时全塞，超过才检索。代价换成"两种模式两条路"，
 * 用同一个 Advisor 内部分支消化（{@link DisclosureToolCallingAdvisor}），不另外开一条链。
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
    public static final String TOOL_NAME = "toolSearchTool";

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
     * <p>索引读的是中文译文（{@code zh-description}），整句本来就能命中，所以这里让模型用一句
     * 自然语言说清要什么能力，不要求它自己拆关键词。提示词与索引必须一起改：只改索引不改提示词，
     * 等于留一段教模型绕开不存在的限制的话。
     *
     * <p><b>只在"藏起来"的那几轮拼它</b>（{@link ToolSearchProperties.Mode}）：全塞时这句话是假的，
     * 而模型会照它去做——先去检索一遍本来就在眼前的东西。
     */
    public static final String SYSTEM_SUFFIX = "\n\n"
            + "**你现在只看得见一个叫 toolSearchTool 的「找工具」工具，其余能力全在它后面。**\n"
            + "看不到合适的工具**不代表没有这个能力**，不要因此回答\"我没有工具\"或\"做不到\"——\n"
            + "在你检索之前，\"我没有那个工具\"这句话**一定**是对的，检索之后往往就不成立了。\n"
            + "所以：**凡是要用工具才能办的事，第一步就是调 toolSearchTool。**\n"
            + "用户在消息里给了网址、文件路径、要跑的命令、邮件、数据口径的，就是要用工具；\n"
            + "只有纯闲聊、纯常识、纯解释类的问题才不需要检索。\n"
            + "需要向用户确认口径时也可以先检索（两件事不冲突，先看清手上有什么再问更准）。\n"
            + "检索时**用一句自然语言说清你要什么能力**（例如「帮我找之前聊过的内容」「打开这个网页」），"
            + "不用自己拆关键词——中文和英文它都认。\n";

    /**
     * 按配置建索引。两档的差别只在"怎么把 query 变成候选"，见 {@link ToolSearchProperties.Index}。
     *
     * <p>分组这一层在<b>里面</b>，两档都一样：{@code categoryFilter} 只有 {@link NamespacedToolIndex}
     * 读，而 hybrid 的两个检索实现各自要被收窄、融合之后才轮到截断。包在外面的话，排在前面的那一组
     * 会先把名额占满，收窄之后反而更少——这是本类唯一一处"顺序决定对错"的地方。
     *
     * <p>选 {@code hybrid} 而没有工具向量库时<b>在这里就失败</b>，不悄悄回落到 Lucene：回落之后
     * 日志上一切正常，而"我明明配了向量检索"这个判断会一直错下去，错到有人拿它做线上决定。
     *
     * <p>中文化（{@code zh-description}）**两档都包在外面**：2026-11-26 换翼之后两翼读的是同一份
     * 中文译文（票 21 的对照里，lucene 吃译文让 base 档从 0.000 到 0.900）。自研的二元组那一档随
     * {@code index=regex} 一起退役了——它恰恰是"描述变长就掉分"的那一个（0.15 → 0.10）。
     *
     * @param properties  检索配置，必填
     * @param vectorStore 工具专用的向量库（{@link ToolVectorStore}），只有 hybrid 用得到，
     *                    {@code lucene} 传 {@code null}
     * @param categories  分组规则，必填
     * @param translator  描述翻译器，两档都喂；不译时传 {@link ToolDescriptionTranslator#NONE}
     * @return 索引，最外层是规则（配了才有），里面依次是分组与那一档的检索实现
     * @throws IllegalStateException {@code index=hybrid} 但没有工具向量库
     */
    public static ToolIndex index(ToolSearchProperties properties, VectorStore vectorStore,
                                  ToolCategories categories, ToolDescriptionTranslator translator) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(categories, "categories");
        Objects.requireNonNull(translator, "translator");
        ToolIndex base = switch (properties.index()) {
            // 两档都吃译文（票 21）：Lucene 按字切中文 query，撞上英文描述一个词都不重合，
            // 只能捞到中文描述的那几个干扰项——那一版它在 base 档是 0.000，接上译文后 0.900。
            case LUCENE -> new NamespacedToolIndex(
                    new TranslatedVectorIndex(new LuceneToolIndex(properties.lucene().minScore()),
                            translator), categories);
            // 关键字翼是 Lucene，不是自研的二元组（票 21）：TF-IDF 排序比"命中次数累加"靠谱，
            // 而且描述一变长，它的 IDF 会把常见字压下去，不像二元组那样越加越容易碰撞。
            case HYBRID -> HybridToolIndex.of(categories,
                    new TranslatedVectorIndex(new LuceneToolIndex(properties.lucene().minScore()),
                            translator),
                    new TranslatedVectorIndex(new VectorToolIndex(requireVectorStore(vectorStore)),
                            translator), properties.hybrid().keywordWeight());
        };
        // 规则层包在**最外面**：它要看到已经融合、已经收窄、已经截断的那一版，命中就把工具提到最前。
        // 包在里面的话，融合会按 RRF 重新排序，"规则置顶"当场失效——而结果看起来只是"规则不太灵"。
        // 没配规则时原样返回 base：空转一层会让所有既有装配断言多包一层壳，收益是零。
        ToolIndex ranked = properties.rules().isEmpty()
                ? base : new RuleToolIndex(base, properties.rules(), categories);
        // 这里只回答"怎么把 query 变成候选"，不回答"这一轮该不该检索"（票 22）。
        // 2026-11-26 之前最外层还包着一个 ChitchatToolIndex：中文寒暄词表，删空即返回零候选。
        // 撤掉它的三条理由，都是事实：
        //   ① 词表与评测题互为因果——那十道 no-search 题就是照着词表写的，1.000 不证明通用性；
        //   ② 它只认"整句由寒暄词拼成"，真正的"没有意义的对话"（含糊、试探、情绪、跑题）判不中；
        //   ③ 意图判定不是检索。业界两条路都不放在这一层：语义路由（Semantic Router 式，
        //      示例话语 embedding + 阈值判 off-topic）或交给模型（BFCL 的 irrelevance 档量的就是
        //      模型该不该调）。我们选后者：检索层无条件给候选，克制的责任在模型。
        // 空 query 也不再挡：它和寒暄是同一类判断，留一条口子就等于留下"检索层判意图"的先例。
        return ranked;
    }

    /**
     * 向量索引要的那份依赖。缺它时把"缺什么、怎么补"写进异常：这句话是排查的唯一线索。
     *
     * <p>缺的不是"容器里那个 VectorStore"——知识库那一份不能拿来用（工具描述会挤进业务问答的召回）。
     * 缺的是工具专用的那一份，它由 {@link ToolVectorStore} 用 {@code EmbeddingModel} 现场建；
     * 建不出来通常就是没配向量模型。
     *
     * @param vectorStore 工具向量库，可能为 {@code null}
     * @return 同一个向量库
     * @throws IllegalStateException 为 {@code null}
     */
    private static VectorStore requireVectorStore(VectorStore vectorStore) {
        if (vectorStore == null) {
            throw new IllegalStateException("claw4j.advisor.tool-search.index 要向量，但工具用的 "
                    + "VectorStore 建不出来。它由 EmbeddingModel 现场构造（表在 " + ToolVectorStore.qualifiedTable()
                    + "），所以要配 claw4j.model.embedding（模型名、key、dimensions）；"
                    + "不想配就把 index 改成 lucene（纯本地，不用向量库）。");
        }
        return vectorStore;
    }

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
    public static DisclosureToolCallingAdvisor.Builder toolLoop(ToolSearchProperties properties,
                                                                    ToolIndex toolIndex,
                                                                    ObservationRegistry observations,
                                                                    int toolCallLimit) {
        return DisclosureToolCallingAdvisor.builder()
                .toolIndex(Objects.requireNonNull(toolIndex, "toolIndex"))
                .properties(Objects.requireNonNull(properties, "properties"))
                .systemMessageSuffix(SYSTEM_SUFFIX)
                .toolCallingManager(ToolCallBudget.toolCallingManager(observations, toolCallLimit));
    }
}
