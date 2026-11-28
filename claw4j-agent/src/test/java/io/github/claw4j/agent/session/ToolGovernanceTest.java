package io.github.claw4j.agent.session;

import io.github.claw4j.agent.audit.DecisionFacts;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.agent.toolaccess.GatedToolIndex;
import io.github.claw4j.agent.toolaccess.ToolSearchProperties;
import io.github.claw4j.agent.audit.AuditAction;
import io.github.claw4j.agent.audit.AuditErrorCode;
import io.github.claw4j.agent.audit.AuditEvent;
import io.github.claw4j.agent.audit.AuditStatus;
import io.github.claw4j.agent.approval.ApprovalRequiredToolCallback;
import io.github.claw4j.agent.approval.ApprovalRequiredTools;
import io.github.claw4j.domain.ApprovalChoice;
import io.github.claw4j.domain.ApprovalVerdict;
import io.github.claw4j.domain.port.Approvals;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import org.springframework.ai.tool.definition.ToolDefinition;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.util.JsonHelper;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具这一层的两端：进来时这个身份能不能用，以及出去之后系统记下了什么。
 *
 * <p>两半问的是同一个问题的两面：一次工具调用由谁决定，又给下一次追问留下了什么。召回那一半
 * 的边界是别把别人的话带回来，审计那一半的边界是别把业务数据抄成第二份
 * （见 {@link #theRawArgumentsNeverLand}）。
 *
 * <p>放在 {@code session} 包里是因为 {@link RecallSessionTool#MAX_RESULT_CHARS} 是包内可见的，
 * 挪到别的包就只能为了测试把它改成 public。
 *
 * <p>审计那一半用假存储而不是真库：这一层验的是该写什么——谁的值取自哪里、摘要怎么截、指纹
 * 什么时候为空、审批编号怎么连上。库那一侧（列宽、唯一索引、视图）由 {@code RunSchemaSqlTest}
 * 在真 Postgres 上验，两者差一层，不重复。
 *
 * <p>假存储只实现 {@code append}：其余方法一律抛。审计只该追加一行，不读不写别的——真有一天
 * 它开始调 {@code stepsOf} 或 {@code finish}，那一条会立刻红，而不是等到某个库慢的下午才被
 * 人发现。
 */
class ToolGovernanceTest {

    // ---------------------------------------------------------------- 审计那一半（ADR-0027）

    private static final String RUN = "run-1";

    private static final String SESSION = "claw4j:user:u-analyst:web:chat-a";

    private static final String TOOL = "querySales";

    private static final Principal CALLER = new Principal("u-analyst");

    /** 真正落下来的事件。审计的形状变了（ADR-0027）：断言看事件，不看 step 行。 */
    private List<AuditEvent> events;

    private ToolCallAudit audit;

    @BeforeEach
    void setUp() {
        events = new ArrayList<>();
        audit = new ToolCallAudit(events::add, new InMemoryFacts());
    }

    private static AuditEvent.Scope scope() {
        return new AuditEvent.Scope(RUN, SESSION, null, null, "u-analyst", "web");
    }

    @Test
    @DisplayName("真跑了一次：两条事件（开始 + 成功），同一次调用指纹相同")
    void anExecutedCallLandsAsTwoEvents() {
        audit.toolStarted(scope(), TOOL, "{\"region\":\"华东\"}", "call_1");
        audit.toolFinished(scope(), TOOL, "{\"region\":\"华东\"}", null, "call_1");

        assertThat(events).hasSize(2);
        assertThat(events.get(0).action()).isEqualTo(AuditAction.TOOL_ACTION_STARTED);
        assertThat(events.get(1).action()).isEqualTo(AuditAction.TOOL_ACTION_FINISHED);
        assertThat(events.get(1).status()).isEqualTo(AuditStatus.SUCCEEDED);
        assertThat(events.get(0).argsDigest())
                .as("两条是同一次调用：指纹算的是同一份参数")
                .isNotNull()
                .isEqualTo(events.get(1).argsDigest());
        assertThat(events.get(0).toolCallId())
                .as("编号进账本前哈希（OpenClaw 的 auditToolCallId）：provider 的字节不原样落库")
                .startsWith("sha256:")
                .isEqualTo(events.get(1).toolCallId())
                .doesNotContain("call_1");
        assertThat(events.get(1).toolCallId()).isEqualTo(events.get(0).toolCallId());

        // 同参数、两个不同的调用：来源键必须不同，否则第二对会撞唯一键被丢掉（票 06）。
        events.clear();
        audit.toolStarted(scope(), TOOL, "{\"region\":\"华东\"}", "call_1");
        audit.toolStarted(scope(), TOOL, "{\"region\":\"华东\"}", "call_2");
        assertThat(events.get(0).argsDigest())
                .as("前提：两次的参数指纹相同")
                .isEqualTo(events.get(1).argsDigest());
        assertThat(events.get(0).sourceId())
                .as("来源键按调用编号算：靠指纹的话第二次那两行根本进不了库")
                .isNotEqualTo(events.get(1).sourceId())
                .startsWith("tool:" + RUN + ":sha256:");
        assertThat(events.get(1).scope().requestedBy()).isEqualTo("u-analyst");
        assertThat(events.get(1).toolName()).isEqualTo(TOOL);
    }

    /**
     * 参数原文与摘要都不进事件。
     *
     * <p>把摘要当「给人看的一格」加回来，这条会红——而它正是审计变成业务数据第二份副本的入口。
     * 判据只有一条：审计记的是动作，不是内容。
     */
    @Test
    @DisplayName("参数原文与摘要都不进事件：审计不成为业务数据的第二份副本")
    void theRawArgumentsNeverLand() {
        String verbatim = "{\"phone\":\"13800138000\",\"note\":\"" + "华东区".repeat(120) + "\"}";

        audit.toolStarted(scope(), TOOL, verbatim, null);
        audit.toolFinished(scope(), TOOL, verbatim, null, null);

        assertThat(events).allSatisfy(event -> {
            assertThat(event.toolName()).isEqualTo(TOOL);
            assertThat(event.errorCode()).isNull();
            assertThat(event.approvalId()).isNull();
            assertThat(event.scope().sessionKey()).isEqualTo(SESSION);
        });
        assertThat(events.toString())
                .as("指纹在，原文不在：一条贴了号码的参数不该整段进库")
                .doesNotContain("13800138000")
                .doesNotContain("华东区");
    }

    /**
     * "这次调用是哪次批准的"：兑现那一侧记下的编号必须落到执行事件上。
     *
     * <p>三条断言各拦一种写法：① 编号根本不落；② 同一轮里两次同参数调用双双带上同一个编号；
     * ③ 键里不带 {@code executionId}（批准从另一轮漏过来，看起来完全正常）。
     */
    @Test
    @DisplayName("兑现过的批准只落在它自己那一轮的执行事件上，而且只落一次")
    void aGrantLandsOnlyOnItsOwnExecution() {
        String args = "{\"to\":\"zhang@example.com\"}";
        String digest = ToolCallAudit.digest("sendEmail", args).orElseThrow();

        audit.granted(RUN, "sendEmail", digest, "approval-7");
        audit.toolFinished(scope(), "sendEmail", args, null, null);
        audit.toolFinished(scope(), "sendEmail", args, null, null);
        audit.toolFinished(new AuditEvent.Scope("run-2", SESSION, null, null, "u-analyst", "web"),
                "sendEmail", args, null, null);

        assertThat(events).hasSize(3);
        assertThat(events.get(0).approvalId()).isEqualTo("approval-7");
        assertThat(events.get(1).approvalId())
                .as("第二条同参数调用不该再搭上那条批准")
                .isNull();
        assertThat(events.get(2).approvalId())
                .as("兑换记在别的 run 上不串味：键里带 executionId")
                .isNull();
    }

    @Test
    @DisplayName("被拒：一条 blocked，没有「开始」那一条，理由是错误码不是文案")
    void aRejectedCallIsOneBlockedEvent() {
        audit.rejected(scope(), TOOL, "{\"region\":\"华东\"}", null);

        assertThat(events).hasSize(1);
        AuditEvent event = events.getFirst();
        assertThat(event.action()).isEqualTo(AuditAction.TOOL_ACTION_FINISHED);
        assertThat(event.status()).isEqualTo(AuditStatus.BLOCKED);
        assertThat(event.errorCode()).isEqualTo(AuditErrorCode.TOOL_BLOCKED);
        assertThat(event.toolName()).isEqualTo(TOOL);
        assertThat(event.scope().requestedBy()).isEqualTo("u-analyst");
    }

    /**
     * 等审批一条都不写。
     *
     * <p>这一条守的是"同一个事实只在一处"：谁提的、批没批、谁批的，都在 {@code approval_request}
     * 那一行上。审计再记一遍，第一个代价是"今天有几条被拒"要 union 两张表，
     * 第二个代价是两张迟早对不上。
     */
    @Test
    @DisplayName("等审批：一条审计事件都不写——那件事的真相在 approval_request 上")
    void anAwaitingCallWritesNothing() {
        audit.granted(RUN, "sendEmail", "digest", "approval-9");

        assertThat(events).as("兑现只是记一条待配对的线索，不是一条事件").isEmpty();
    }

    @Test
    @DisplayName("工具抛异常：结束事件是 failed + tool_failed，异常类型名不进库")
    void aFailingToolIsStillAnExecution() {
        audit.toolFinished(scope(), TOOL, "{}", "IllegalStateException", null);

        AuditEvent event = events.getFirst();
        assertThat(event.status()).isEqualTo(AuditStatus.FAILED);
        assertThat(event.errorCode()).isEqualTo(AuditErrorCode.TOOL_FAILED);
        assertThat(event.toString())
                .as("类型名是开放的，而 error_code 那一列是闭合口径；要查类型看 trace")
                .doesNotContain("IllegalStateException");
    }

    @Test
    @DisplayName("参数收不全（流式被拦在授权那一道）：指纹留空，事件照写")
    void aHalfReceivedArgumentLeavesNoFingerprint() {
        audit.rejected(scope(), TOOL, "{\"region\":", null);

        assertThat(events.getFirst().argsDigest()).isNull();
        assertThat(events.getFirst().status()).isEqualTo(AuditStatus.BLOCKED);
    }

    @Test
    @DisplayName("没有轮次编号也写：「发生过」比字段齐全重要，只是那一格空")
    void anEventWithoutARunIsStillWritten() {
        audit.toolFinished(AuditEvent.Scope.empty(), TOOL, "{}", null, null);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().scope().executionId()).isNull();
        assertThat(events.getFirst().toolName()).isEqualTo(TOOL);
    }

    @Test
    @DisplayName("没有账本：整体空转，判定不受影响")
    void withoutALedgerItSimplyWritesNothing() {
        ToolCallAudit withoutLedger = new ToolCallAudit(null);

        assertThatCode(() -> withoutLedger.toolFinished(scope(), TOOL, "{}", null, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> withoutLedger.rejected(scope(), TOOL, "{}", null))
                .doesNotThrowAnyException();
    }

    /**
     * 账本写的时候炸了：仍然不该让一次工具调用失败。审计是记账，不是回答的前置条件。
     */
    @Test
    @DisplayName("写审计时抛异常：吞掉，不影响这次工具调用")
    void aBrokenLedgerNeverBreaksTheCall() {
        ToolCallAudit broken = new ToolCallAudit(event -> {
            throw new IllegalStateException("库挂了");
        });

        assertThatCode(() -> broken.toolFinished(scope(), TOOL, "{}", null, null))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- 召回那一半（范围可配）

    /** 这个身份在一条通道的一个 chat 里。键一律由 {@link SessionKeys} 派生，不手拼。 */
    private static String sessionOf(String userId, String channel, String chatId) {
        return SessionKeys.sessionIdOf(new Principal(userId), channel, chatId);
    }

    /**
     * 当前这条对话。2026-11-25 起会话 id 就是会话键（ADR-0026 修订），没有段后缀。
     *
     * <p>召回的身份校验是前缀匹配（{@code startsWith(PREFIX + owner + ":")}），这几条用例
     * 真正钉的是"换一个人就看不到"——键的形状变了也得照样成立。
     */
    private static final String CURRENT_SESSION = SessionKeys.sessionIdOf(
            new Principal("u-1"), "web", "chat-a");

    /** 同一个人的另外两段对话：网页上的另一个 chat，以及一个项目群。 */
    private static final String PRIVATE_ELSEWHERE = sessionOf("u-1", "web", "chat-b");

    private static final String GROUP_ELSEWHERE = sessionOf("u-1", "feishu", "oc_group");

    /**
     * 在假仓库里登记一段会话，归属键从会话 id 的前缀取。
     *
     * <p><b>为什么每次都要登记</b>：真库里 {@code findEventsByUserId} 靠 {@code ai_session.user_id}
     * 把事件找出来；假仓库照抄了这条路径。不登记的话，"改回按 user_id 查"这个反例
     * 会因为假仓库什么都没有而红，而不是因为泄露——那种红是假红，它挡不住真正的回退。
     */
    private static void open(FakeSessions sessions, String sessionId) {
        int from = SessionKeys.PREFIX.length();
        int to = sessionId.indexOf(SessionKeys.SESSION_SEPARATOR, from);
        sessions.create(CreateSessionRequest.builder()
                .id(sessionId)
                .userId(sessionId.substring(from, to))
                .build());
    }

    @Test
    @DisplayName("self 档只召回本段对话（含压缩归档过的）：私聊与群聊都带不回来，「没找到」还要说清范围")
    void selfScopeRecallsOnlyThisConversation() {
        FakeSessions sessions = new FakeSessions();
        open(sessions, CURRENT_SESSION);
        open(sessions, PRIVATE_ELSEWHERE);
        open(sessions, GROUP_ELSEWHERE);
        sessions.append(CURRENT_SESSION, "华东区的成交口径按签约主体算，不按开票主体");
        sessions.appendArchived(CURRENT_SESSION, "华东区那批单子按出库时间归月，不看开票日");
        sessions.append(PRIVATE_ELSEWHERE, "华东上周成交额 128,400.00，先别外传");
        sessions.append(GROUP_ELSEWHERE, "群里说过：华东那边先按开票主体报");

        RecallSessionTool self = tool(sessions, SessionVisibility.SELF);
        String recalled = self.call("{\"query\":\"华东\"}",
                toolContext(new Principal("u-1"), CURRENT_SESSION));

        assertThat(recalled).contains("按签约主体算");
        assertThat(recalled)
                .as("归档过的那句也在：压缩把原文折成摘要之后，它是这个工具唯一的价值来源")
                .contains("按出库时间归月");
        assertThat(recalled)
                .as("私聊里的数字被带进群聊，就是一次没人复核的泄露——听众不一样，权限指纹挡不住")
                .doesNotContain("128,400")
                .doesNotContain("先按开票主体报");
        assertThat(recalled)
                .as("self 下不带出处：那一段恒等于当前这一段，重复只是白花 token")
                .doesNotContain(CURRENT_SESSION);

        assertThat(self.call("{\"query\":\"先别外传\"}",
                toolContext(new Principal("u-1"), CURRENT_SESSION)))
                .as("「这一段没说过」与「整个部署都没说过」在模型那里都是「没说过」，"
                        + "在排查时是两件事——所以文案必须带范围")
                .contains("没有找到")
                .contains("只有当前这一段对话");
    }

    @Test
    @DisplayName("默认 all：一个用户能召回另一个用户说过的那句，且结果带出处")
    void defaultVisibilityRecallsAcrossUsers() {
        String otherUsers = SessionKeys.sessionIdOf(new Principal("u-2"), "web", "chat-z");
        FakeSessions sessions = new FakeSessions();
        open(sessions, CURRENT_SESSION);
        open(sessions, otherUsers);
        sessions.append(CURRENT_SESSION, "华东区的成交口径按签约主体算");
        sessions.append(otherUsers, "华东区口径按签约主体算——这条是 u-2 说的");

        assertThat(SessionVisibilityProperties.defaults().scope())
                .as("缺键落 all：它的默认值是 all，收窄必须显式配")
                .isEqualTo(SessionVisibility.ALL);

        String recalled = tool(sessions).call("{\"query\":\"华东区口径\"}",
                toolContext(new Principal("u-1"), CURRENT_SESSION));

        assertThat(recalled)
                .as("这就是默认 all 的代价：read-only 身份能召回别人的会话内容。这是它的默认，不是 bug")
                .contains("这条是 u-2 说的");
        assertThat(recalled)
                .as("跨会话必须带出处，否则模型会把两段对话揉成一段回答，而那句混出来的话无从对照")
                .contains(otherUsers);
    }

    @Test
    @DisplayName("四个值各归各位：agent 与 all 同结果、tree 等同 self，工具描述跟着生效范围走")
    void everyValueTakesItsOwnBranch() {
        String otherUsers = SessionKeys.sessionIdOf(new Principal("u-2"), "web", "chat-z");
        FakeSessions sessions = new FakeSessions();
        open(sessions, CURRENT_SESSION);
        open(sessions, otherUsers);
        sessions.append(CURRENT_SESSION, "华东区的成交口径按签约主体算");
        sessions.append(otherUsers, "华东区口径按签约主体算——这条是 u-2 说的");
        ToolContext context = toolContext(new Principal("u-1"), CURRENT_SESSION);
        String query = "{\"query\":\"华东区口径\"}";

        assertThat(tool(sessions, SessionVisibility.AGENT).call(query, context))
                .as("我们只有一个 agent 命名空间：「本 agent 的全部会话」就是全部会话")
                .isEqualTo(tool(sessions, SessionVisibility.ALL).call(query, context))
                .contains("这条是 u-2 说的");
        assertThat(tool(sessions, SessionVisibility.TREE).call(query, context))
                .as("子 Agent 会话今天不落会话库，tree 的差集是空的——等落库了再改这一处")
                .isEqualTo(tool(sessions, SessionVisibility.SELF).call(query, context))
                .doesNotContain("这条是 u-2 说的");

        assertThat(RecallSessionTool.descriptionFor(SessionVisibility.ALL))
                .as("描述写死成「看不到别的会话」的话，all 下模型会主动拒绝一次查得到的召回")
                .doesNotContain("看不到你在别的会话")
                .contains("全部会话");
        assertThat(RecallSessionTool.descriptionFor(SessionVisibility.SELF))
                .contains("看不到你在别的会话");
        assertThat(RecallSessionTool.descriptionFor(SessionVisibility.TREE))
                .as("tree 的描述跟着它的实际范围走，不跟着它的名字走")
                .contains("看不到你在别的会话");
    }

    @Test
    @DisplayName("合不拢就不查：换人、缺身份、缺会话都不退化成「先查了再说」（self 档）")
    void guardsRefuseInsteadOfWidening() {
        FakeSessions sessions = new FakeSessions();
        open(sessions, CURRENT_SESSION);
        sessions.append(CURRENT_SESSION, "华东区的成交口径按签约主体算，上周成交额 99,999.00");
        RecallSessionTool self = tool(sessions, SessionVisibility.SELF);
        String query = "{\"query\":\"华东\"}";

        assertThat(self.call(query, toolContext(new Principal("u-2"), CURRENT_SESSION)))
                .as("u-2 拿到了 u-1 那一段的会话 id 也查不出东西")
                .doesNotContain("签约主体")
                .contains("不属于当前身份");

        // "降权之后不该再念出旧数字"那一条随权限指纹下线（ADR 代价第 3 条）：身份现在只有
        // userId，没有第二种"权限版本"可掺，于是同一个人换了配置仍然是同一段会话。
        // 这一步是明码标价的代价，不是回归——它记在 ADR-0030 的缺口表里。

        assertThat(self.call(query, new ToolContext(
                Map.of(ChatMemory.CONVERSATION_ID, CURRENT_SESSION))))
                .as("有会话没身份：分不出该不该给")
                .contains("没有携带身份")
                .doesNotContain("签约主体");
        assertThat(self.call(query))
                .as("单参入口没有 ToolContext，同样按没身份处理")
                .contains("没有携带身份");
        assertThat(self.call(query,
                new ToolContext(Map.of(ContextKeys.PRINCIPAL, new Principal("u-1")))))
                .as("按 principal 猜会话，就是改回按 user_id 查：那正是私聊漏进群聊的那条路")
                .contains("没有携带会话标识")
                .doesNotContain("签约主体");
    }

    @Test
    @DisplayName("参数写错了回一句话而不是整轮 500；match_mode 也真的传下去了")
    void badArgumentsComeBackAsAdvice() {
        FakeSessions sessions = new FakeSessions();
        open(sessions, CURRENT_SESSION);
        sessions.append(CURRENT_SESSION, "华东区的成交口径按签约主体算");
        sessions.append(CURRENT_SESSION, "华南那边的口径还是按开票主体");
        RecallSessionTool tool = tool(sessions);
        ToolContext context = toolContext(new Principal("u-1"), CURRENT_SESSION);

        assertThat(tool.call("{\"query\":\"华东,口径\",\"match_mode\":\"all\"}", context))
                .as("all 只留同时命中全部关键词的那段")
                .contains("按签约主体算")
                .doesNotContain("华南");
        assertThat(tool.call("{\"match_mode\":\"all\"}", context))
                .as("query 必填")
                .contains("召回参数不合法");
        assertThat(tool.call("{\"query\":\"华东\",\"since\":\"上周\"}", context))
                .contains("召回参数不合法");
        assertThat(tool.call("{\"query\":\"华东\",\"page\":\"第一页\"}", context))
                .contains("召回参数不合法");
        assertThat(tool.call("{\"query\":\"华东\",\"match_mode\":\"大部分\"}", context))
                .contains("召回参数不合法");
    }

    @Test
    @DisplayName("一条长对话不会把上下文吃满：条数与总字数都封顶")
    void truncatesWhatItBringsBack() {
        FakeSessions sessions = new FakeSessions();
        open(sessions, CURRENT_SESSION);
        for (int i = 0; i < 40; i++) {
            sessions.append(CURRENT_SESSION, "华东口径第 " + i + " 段：" + "按签约主体算。".repeat(60));
        }

        String recalled = tool(sessions).call("{\"query\":\"华东口径\"}",
                toolContext(new Principal("u-1"), CURRENT_SESSION));

        assertThat(recalled.length())
                .as("条数封顶后仍可能是一条一万字，整段必须再按字符裁一次")
                .isLessThan(RecallSessionTool.MAX_RESULT_CHARS + 200);
        assertThat(recalled).contains("已截断");
    }

    // ---------------------------------------------------------------- 手写 schema

    /**
     * 手写的入参 Schema 必须能被解析成 Map。
     *
     * <p>不解析的话症状很难查：模型调用方在发请求之前就要转，坏一个，整轮的工具清单都发不出去，
     * 而模型那边只会看到「没有工具」。这条盯的就是这种沉默的整轮失效。
     */
    @Test
    @DisplayName("工具的手写入参 Schema 必须是合法 JSON：少转义一个引号，那一轮所有工具都发不出去")
    void handwrittenToolSchemasAreValidJson() {
        List<ToolCallback> handwritten = List.of(tool(new FakeSessions()));
        JsonHelper json = new JsonHelper();

        for (ToolCallback callback : handwritten) {
            assertThatCode(() -> json.fromJsonToMap(callback.getToolDefinition().inputSchema()))
                    .as("%s 的 inputSchema 解析不了。模型调用方在发请求之前就要把它转成 Map："
                            + "坏一个，整轮的工具清单都发不出去，而模型那边只会看到「没有工具」"
                            + "（2026-11-16 实测：Objective 描述里的「更\"专业\"」少了转义，"
                            + "DeepSeek 与 OpenAI 两条路都在这里炸）", callback.getToolDefinition().name())
                    .doesNotThrowAnyException();
        }
    }

    private static RecallSessionTool tool(FakeSessions sessions) {
        return tool(sessions, SessionVisibility.DEFAULT);
    }

    /**
     * 按指定范围装配：假仓库同时充当 {@link CrossSessionEvents}。
     *
     * <p>跨会话那一路在真库里是一条 SQL，这里用"遍历全部会话 + 框架自己的
     * {@code EventFilter.apply}"顶替：范围解析（走哪个分支、核不核前缀）是要验的东西，
     * SQL 的正确性由 {@code SearchSqlTest} 在真库上验，两件事不在一层。
     */
    private static RecallSessionTool tool(FakeSessions sessions, SessionVisibility visibility) {
        return new RecallSessionTool(new SessionRecallScope(sessions, sessions::allEvents, visibility));
    }

    private static ToolContext toolContext(Principal principal, String sessionId) {
        return new ToolContext(Map.of(
                ContextKeys.PRINCIPAL, principal,
                // 宿主（ChatClientAgentPort）写进去的那一把键，工具只认这一个。
                ChatMemory.CONVERSATION_ID, sessionId));
    }

    /**
     * 假会话仓库：只做两件事——按 userId 找事件、按框架的 {@link EventFilter} 过滤。
     *
     * <p>过滤交给框架自己的 {@code matches}，不自己写字符串包含：
     * 自己实现一遍的话，测的就是"我以为的关键词匹配"，而真跑起来语义可能完全不同
     * （大小写、多词组合、归档事件剔不剔），这种测试在换实现时一声不响地继续绿。
     */
    private static final class FakeSessions implements SessionService {

        private final Map<String, Session> sessions = new LinkedHashMap<>();

        private final Map<String, List<SessionEvent>> events = new LinkedHashMap<>();

        private int eventSequence;

        @Override
        public Session create(CreateSessionRequest request) {
            Session session = Session.builder()
                    .id(request.id())
                    .userId(request.userId())
                    .createdAt(Instant.now())
                    .build();
            sessions.put(session.id(), session);
            return session;
        }

        void append(String sessionId, String text) {
            append(sessionId, text, false);
        }

        /** 压缩归档过的事件：{@code excludeArchived} 默认 false，所以它们必须还能被召回。 */
        void appendArchived(String sessionId, String text) {
            append(sessionId, text, true);
        }

        private void append(String sessionId, String text, boolean archived) {
            events.computeIfAbsent(sessionId, key -> new ArrayList<>())
                    .add(SessionEvent.builder()
                            .id("e-" + (++eventSequence))
                            .sessionId(sessionId)
                            .timestamp(Instant.now().plusSeconds(eventSequence))
                            .message(new UserMessage(text))
                            .archived(archived)
                            .build());
        }

        @Override
        public Session findById(String id) {
            return sessions.get(id);
        }

        @Override
        public List<Session> findByUserId(String userId) {
            return sessions.values().stream().filter(s -> s.userId().equals(userId)).toList();
        }

        @Override
        public void delete(String id) {
            sessions.remove(id);
            events.remove(id);
        }

        @Override
        public int deleteExpiredSessions(Instant now) {
            return 0;
        }

        @Override
        public void appendEvent(SessionEvent event) {
            events.computeIfAbsent(event.getSessionId(), key -> new ArrayList<>()).add(event);
        }

        @Override
        public List<SessionEvent> getEvents(String sessionId, EventFilter filter) {
            return filter.apply(events.getOrDefault(sessionId, List.of()));
        }

        /**
         * 跨会话那一路的替身：全部会话的事件放在一起再按框架的 filter 过一遍。
         *
         * <p>不按 {@code userId} 分组——那正是 {@code findEventsByUserId} 做的事，而这条用例要
         * 证明的是跨用户也查得到。照它分组的话，交叉可见那条会因为替身先按用户筛过而绿，
         * 红不了真的回退。
         */
        List<SessionEvent> allEvents(EventFilter filter) {
            return filter.apply(events.values().stream().flatMap(List::stream).toList());
        }

        @Override
        public List<SessionEvent> findEventsByUserId(String userId, EventFilter filter) {
            List<SessionEvent> mine = sessions.values().stream()
                    .filter(session -> userId.equals(session.userId()))
                    .flatMap(session -> events.getOrDefault(session.id(), List.of()).stream())
                    .toList();
            return filter.apply(mine);
        }

        /** 压缩与本票无关：测试不压，但接口要求有实现。 */
        @Override
        public CompactionResult compact(String sessionId, CompactionTrigger trigger, CompactionStrategy strategy) {
            return null;
        }
    }

    /**
     * 审批交接的替身：只在本进程内。
     *
     * <p>真的那份落在 {@code execution_decision_fact} 一张表里（ADR-0037 票 05），这一层测的是
     * "编号只落在它自己那次执行上"，与存储无关——所以替身够用，也不必为它起一个库。
     */
    private static final class InMemoryFacts implements DecisionFacts {

        private final java.util.Map<String, String> facts = new java.util.HashMap<>();

        @Override
        public void grant(String executionId, String toolName, String argsDigest, String approvalId) {
            facts.put(executionId + '|' + toolName + '|' + argsDigest, approvalId);
        }

        @Override
        public String take(String executionId, String toolName, String argsDigest) {
            return facts.remove(executionId + '|' + toolName + '|' + argsDigest);
        }

        @Override
        public int prune(java.time.Duration age) {
            return 0;
        }
    }

    // ------------------------------------------------- 审批闸那一半（票 05，2026-11-23 并入）

    /**
     * 非 exec 工具的那道闸（票 05）：执行前问一次，没批准就<b>回一条工具结果</b>。
     *
     * <p>判据的重点不是"拦住了"，是"拦的方式"：抛异常会让框架把这一轮打成失败，或者让模型拿到
     * 一句"出错了"；回一条带理由的结果，模型才知道"这件事在等谁批准、不要重试"。
     *
     * <p>这六个用例原来住在 {@code ApprovalRequiredToolCallbackTest}：v0 的 {@code claw4j-agent}
     * 已经 14 个测试文件（上限 14），本票的 {@code MessageAuditTest} 要一个槽，按
     * {@code scripts/verify.sh} 的"先合并不抬上限"挪进来。断言一字未改。
     */
    @Test
    @DisplayName("被拦下只写一条 blocked、没有 started；等审批那一条什么都不写（ADR-0037 票 01）")
    void blockedToolWritesOnlyFinished() {
        events.clear();
        // ① 没有身份：再等也不会通过，所以审计里是 blocked 而不是 failed；
        //    动作没有开始，就只有结束那一条。
        ApprovalRequiredToolCallback withoutIdentity = new ApprovalRequiredToolCallback(
                new GatedTool(TOOL), new RecordingGate(), audit);

        String result = withoutIdentity.call("{}", gateContextWithoutPrincipal());

        assertThat(result).startsWith(ApprovalRequiredToolCallback.DENIAL_PREFIX);
        assertThat(events).as("只有一条：没有 started").hasSize(1);
        AuditEvent blocked = events.getFirst();
        assertThat(blocked.action()).isEqualTo(AuditAction.TOOL_ACTION_FINISHED);
        assertThat(blocked.status()).isEqualTo(AuditStatus.BLOCKED);
        assertThat(blocked.errorCode()).isEqualTo(AuditErrorCode.TOOL_BLOCKED);
        assertThat(blocked.toolName()).isEqualTo(TOOL);
        assertThat(blocked.scope().executionId()).isEqualTo("run-gate");
        assertThat(blocked.scope().agentId()).as("工具事件也带 agent 归属").isEqualTo("ops");

        // ② 登记不上审批也一样：它同样永远不会被放行。
        events.clear();
        RecordingGate noFacility = new RecordingGate();
        noFacility.registered = false;
        new ApprovalRequiredToolCallback(new GatedTool(TOOL), noFacility, audit)
                .call("{\"to\":\"zhang@example.com\"}", gateContext());
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().status()).isEqualTo(AuditStatus.BLOCKED);

        // ③ 登记上了就什么都不写：等审批这件事的真相在 approval_request 一张表里。
        events.clear();
        new ApprovalRequiredToolCallback(new GatedTool(TOOL), new RecordingGate(), audit)
                .call("{\"to\":\"zhang@example.com\"}", gateContext());
        assertThat(events).as("等审批不写审计").isEmpty();
    }

    @Test
    @DisplayName("没有批准：回一条带编号与「不要重试」的结果，delegate 一次都不执行")
    void withoutApprovalItReturnsAResultInsteadOfThrowing() {
        GatedTool tool = new GatedTool(TOOL);
        RecordingGate gate = new RecordingGate();
        ApprovalRequiredToolCallback callback = new ApprovalRequiredToolCallback(tool, gate);

        String result = callback.call("{\"to\":\"zhang@example.com\"}", gateContext());

        assertThat(tool.calls()).isZero();
        assertThat(result)
                .contains("先审批")
                .contains("ap-1")
                .contains("没有执行")
                .contains("不要重试")
                .contains("没有新的输出");
        assertThat(gate.asked).isEqualTo(1);
        assertThat(gate.lastAsk.owner()).isEqualTo("u-mailer");
        assertThat(gate.lastAsk.toolName()).isEqualTo(TOOL);
        assertThat(gate.lastAsk.origin()).isNotNull();
        assertThat(gate.lastAsk.origin().sessionKey()).isEqualTo("claw4j:user:u-mailer:http:chat-ap");
    }

    @Test
    @DisplayName("已有批准：直接执行，不再问")
    void anExistingGrantRunsTheTool() {
        GatedTool tool = new GatedTool(TOOL);
        RecordingGate gate = new RecordingGate();
        gate.granted = true;
        ApprovalRequiredToolCallback callback = new ApprovalRequiredToolCallback(tool, gate);

        String result = callback.call("{\"to\":\"zhang@example.com\"}", gateContext());

        assertThat(tool.calls()).isEqualTo(1);
        assertThat(result).isEqualTo("已发送");
        assertThat(gate.asked).isZero();
    }

    @Test
    @DisplayName("没有身份：不登记也不执行——批给谁没有答案")
    void withoutIdentityItRefusesWithoutAsking() {
        GatedTool tool = new GatedTool(TOOL);
        RecordingGate gate = new RecordingGate();
        ApprovalRequiredToolCallback callback = new ApprovalRequiredToolCallback(tool, gate);

        String result = callback.call("{}", null);

        assertThat(tool.calls()).isZero();
        assertThat(result).startsWith("没有权限");
        assertThat(gate.asked).isZero();
    }

    @Test
    @DisplayName("参数不成 JSON 对象：不执行，也不登记一条看不懂的请求")
    void malformedArgumentsAreRefused() {
        GatedTool tool = new GatedTool(TOOL);
        RecordingGate gate = new RecordingGate();
        ApprovalRequiredToolCallback callback = new ApprovalRequiredToolCallback(tool, gate);

        String result = callback.call("{\"to\":", gateContext());

        assertThat(tool.calls()).isZero();
        assertThat(result).contains("不是一段完整的 JSON");
        assertThat(gate.asked).isZero();
    }

    @Test
    @DisplayName("只包名单里那几个：读类工具没有这道闸，不会每读一次弹一次")
    void onlyListedToolsAreGated() {
        List<ToolCallback> tools = List.of(new GatedTool("readFile"), new GatedTool(TOOL));

        List<ToolCallback> gated = io.github.claw4j.agent.AgentAutoConfiguration.withApprovalGate(
                tools, new RecordingGate(), new ApprovalRequiredTools(Set.of(TOOL)), null);

        assertThat(gated.get(0)).isSameAs(tools.get(0));
        assertThat(gated.get(1)).isInstanceOf(ApprovalRequiredToolCallback.class);
    }

    @Test
    @DisplayName("名单是空的时候一个都不包：默认部署不变成「每步都要批」")
    void anEmptyListWrapsNothing() {
        List<ToolCallback> tools = List.of(new GatedTool(TOOL));

        assertThat(io.github.claw4j.agent.AgentAutoConfiguration.withApprovalGate(
                tools, new RecordingGate(), ApprovalRequiredTools.none(), null))
                .containsExactlyElementsOf(tools);
    }

    private static ToolContext gateContext() {
        return new ToolContext(Map.of(
                ContextKeys.PRINCIPAL, new Principal("u-mailer"),
                ContextKeys.EXECUTION_ID, "run-gate",
                ContextKeys.AGENT_ID, "ops",
                ContextKeys.CHANNEL, "http",
                ContextKeys.CHAT_REF, "chat-ap",
                ChatMemory.CONVERSATION_ID, "claw4j:user:u-mailer:http:chat-ap"));
    }

    private static ToolContext gateContextWithoutPrincipal() {
        return new ToolContext(Map.of(
                ContextKeys.EXECUTION_ID, "run-gate",
                ContextKeys.AGENT_ID, "ops",
                ChatMemory.CONVERSATION_ID, "claw4j:user:u-mailer:http:chat-ap"));
    }

    /** 只回一句话的工具桩：验的是"有没有被调到"，不是它做了什么。 */
    private static final class GatedTool implements ToolCallback {

        private final String name;

        private int calls;

        private GatedTool(String name) {
            this.name = name;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder().name(name).description("用例").inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput) {
            calls++;
            return "已发送";
        }

        private int calls() {
            return calls;
        }
    }

    // ---------------------------------------------------------------- 准入闸那一半（票 29 / ADR-0039）

    /**
     * 通用工具的准入闸：点到了名就提到候选最前面，没点到名的留在原位——<b>一个都不删</b>。
     *
     * <p>2026-11-28 改的口径。以前是"没点名就删"，理由是票 28 量出来 240 条里 26 条误调集中在
     * 四个"对什么都能凑合"的工具上。但"删"的判据是一张手写的触发词表（闭集）：用户写了
     * 「用 bash 数一下 examples 下有多少个 .md 文件」，检索也确实召回了 {@code Bash}，
     * 只因为表里没写 {@code bash}，它就被砍掉，模型只好拿 {@code Glob} 反复试到上限。
     * 检索能召回就是检索给这句子下的结论，闸只该调名次，不该替检索否决用户。
     */
    @Test
    @DisplayName("通用工具点名就提到最前；没点名也留着（排在后面），一个候选都不删")
    void gatePromotesNamedGenericToolWithoutDroppingAnything() {
        ToolSearchProperties.Rule bash = new ToolSearchProperties.Rule("跑|编译|测试", List.of("Bash"), true);
        ToolSearchProperties.Rule finance = new ToolSearchProperties.Rule("报销", List.of("finance_export"), false);
        ToolIndex index = GatedToolIndex.of(
                new FixedIndex(List.of("Bash", "weather_now", "book_search", "sun_times", "finance_export")),
                List.of(bash, finance));

        assertThat(names(index.search(request("讲个笑话"))))
                .as("没点名：Bash 留在原地，不删——删掉它等于替检索否决这句话")
                .hasSize(5)
                .contains("Bash", "weather_now", "book_search");
        assertThat(names(index.search(request("在仓库里跑一遍单元测试"))))
                .as("点名了就得排最前：这是「该调没调」那一侧的错，比误调更难被发现")
                .startsWith("Bash");
        assertThat(names(index.search(request("这个月报销多少"))))
                .as("没标 gate 的规则不进这一层：finance_export 的行为一行没变")
                .contains("finance_export");

        ToolSearchProperties.Rule onlyFinance =
                new ToolSearchProperties.Rule("报销", List.of("finance_export"), false);
        assertThat(GatedToolIndex.of(index, List.of(onlyFinance)))
                .as("一条 gate 规则都没有时原样返回：空转一层只会让装配断言多包一层壳")
                .isSameAs(index);
    }

    /** 假索引：固定返回几个名字，名次就是列表顺序。这一层要验的是"删/不删"，不是"像不像"。 */
    private static final class FixedIndex implements ToolIndex {

        private final List<String> toolNames;

        private FixedIndex(List<String> toolNames) {
            this.toolNames = toolNames;
        }

        @Override
        public ToolSearchResponse search(ToolSearchRequest toolSearchRequest) {
            List<ToolReference> references = toolNames.stream()
                    .map(name -> new ToolReference(name, 1.0, name + " 的说明"))
                    .toList();
            return ToolSearchResponse.builder()
                    .toolReferences(references)
                    .totalMatches(references.size())
                    .build();
        }

        @Override
        public void indexTool(String sessionId, ToolReference toolReference) {
            // 固定索引：这一层不测"怎么进的索引"。
        }

        @Override
        public void clearIndex(String sessionId) {
            // 同上。
        }
    }

    private static ToolSearchRequest request(String query) {
        return new ToolSearchRequest(SESSION, query, 5, null);
    }

    private static List<String> names(ToolSearchResponse response) {
        return response.toolReferences().stream().map(ToolReference::toolName).toList();
    }

    /** 假审批门：记下被问了什么，按 {@code granted} 决定兑现与否。 */
    private static final class RecordingGate implements Approvals {

        private boolean granted;

        /** false 表示这个部署没有审批设施：问也问不出编号。 */
        private boolean registered = true;

        private int asked;

        private Approvals.Ask lastAsk;

        @Override
        public Optional<Approvals.Grant> redeem(String owner, String toolName, String argsDigest) {
            return granted
                    ? Optional.of(new Approvals.Grant("ap-old", Instant.now()))
                    : Optional.empty();
        }

        @Override
        public Optional<String> ask(Approvals.Ask ask) {
            asked++;
            lastAsk = ask;
            return registered ? Optional.of("ap-1") : Optional.empty();
        }

        @Override
        public Result decide(String approvalId, String approver, ApprovalChoice choice, String reason) {
            return new Result(ApprovalVerdict.NOT_FOUND, "假审批不落结论");
        }

        @Override
        public Optional<Pending> find(String approvalId) {
            return Optional.empty();
        }
    }
}
