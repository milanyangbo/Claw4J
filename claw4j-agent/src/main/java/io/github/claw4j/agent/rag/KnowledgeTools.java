package io.github.claw4j.agent.rag;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Objects;

/**
 * 知识库检索工具。**它是工具，不是自动注入的上下文**。
 *
 * <p><b>为什么不做成 RAG Advisor</b>：自动注入看起来更"RAG"——每轮把检索结果塞进系统提示词，
 * 模型不需要知道有这回事。代价是它绕开三件事：哪些片段能进上下文由谁判（鉴权）、
 * 模型看到的东西被改过而 run 里没有留痕（审计）、一个只读身份与一个管理员拿到的是不是同一段
 * （分级）。这三件事在工具这条路上都是现成的：权限登记表、run 级审计、按身份鉴权。
 *
 * <p><b>它的返回里一定有出处</b>：每段前面是 {@code 文档路径 第 N 段}。这是这一层存在的理由——
 * "回答带着出处"与"把某段文档当成答案吐出来"的区别全在这里。所以格式化只在这一处做，
 * 两处拼就会写出两种格式，而其中一种一定漏掉出处。
 *
 * <p><b>"没找到"要说清是哪一种没找到</b>：库里一段都没有（索引没建起来 / 目录配错了）
 * 与"库里有 N 段但没有一段相关"是两件事。前者是部署问题，后者是知识确实没写。
 * 两者都回一句"没有相关资料"的话，运维会去查模型，而真正的问题在配置里。
 */
public class KnowledgeTools {

    /** 工具名。权限表与档位表都按它登记，写两份字面量迟早会漂。 */
    static final String TOOL_NAME = "searchDocs";

    /**
     * 描述里三件事必须说清：**什么时候用**、**不用它查数**、**拿不到不要编**。
     *
     * <p>第三句不是修辞。工具返回空结果时，模型最常见的两种反应是"那我按常识答"与
     * "我这边没有这个能力"——前者会编出一条听起来很像政策的规则，后者让用户以为系统坏了。
     * 所以描述里直接给出第三种动作：照实说没查到，并指出可以问谁。
     */
    private static final String DESCRIPTION = """
            在内部知识库（政策、规定、流程、口径、条款说明）里检索相关段落，返回的每一段都带出处\
            （文档路径 + 第几段）。

            什么时候用：用户问"…怎么规定的""…的政策是什么""…的流程""…算不算"这类问题。
            什么时候不用：问具体的数字、金额、订单、客户这类业务数据时用 querySales，不要用这个。
            拿不到怎么办：如果返回的是"没有检索到"，就照实告诉用户知识库里没有这条，不要凭常识补一段；
            也不要因此说"我没有查询能力"——检索本身是通的。
            """;

    private final KnowledgeBase knowledge;

    public KnowledgeTools(KnowledgeBase knowledge) {
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
    }

    /**
     * @param query 直接写用户的原话即可，不需要先分词（内部会切）
     */
    @Tool(name = TOOL_NAME, description = DESCRIPTION)
    public String searchDocs(
            @ToolParam(description = "要检索的问题或关键词，直接写用户的原话") String query) {
        List<KnowledgeBase.Hit> hits = knowledge.search(query);
        if (hits.isEmpty()) {
            int chunks = knowledge.chunkCount();
            if (chunks == 0) {
                return "知识库里一段文档都没有（现在是 0 段），这是部署侧的问题，不是问题问得不对。"
                        + "请如实告诉用户知识库还没建起来，不要凭常识作答。";
            }
            return "知识库里 " + chunks + " 段文档都没有匹配到这个问题。"
                    + "请如实告诉用户没有查到相关规定，不要凭常识补一条规则出来。";
        }
        StringBuilder answer = new StringBuilder();
        answer.append("检索到 ").append(hits.size()).append(" 段（按相关度排序）。")
                .append("回答时要带上出处，格式就用下面每段开头那一行：\n");
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeBase.Hit hit = hits.get(i);
            answer.append('\n').append('[').append(i + 1).append("] ").append(hit.citation()).append('\n')
                    .append(hit.text()).append('\n');
        }
        return answer.toString();
    }
}
