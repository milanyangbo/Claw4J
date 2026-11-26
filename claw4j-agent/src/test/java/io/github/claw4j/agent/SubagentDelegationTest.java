package io.github.claw4j.agent;

import io.github.claw4j.agent.audit.AuditAction;
import io.github.claw4j.agent.audit.AuditErrorCode;
import io.github.claw4j.agent.audit.AuditEvent;
import io.github.claw4j.agent.audit.AuditStatus;
import io.github.claw4j.agent.audit.ToolCallAudit;
import org.springframework.ai.tool.toolsearch.index.lucene.LuceneToolIndex;
import io.github.claw4j.agent.toolaccess.ToolSearch;
import io.github.claw4j.agent.toolaccess.ToolSearchProperties;
import io.github.claw4j.agent.toolaccess.ToolsProperties;
import io.github.claw4j.agent.turn.ToolCallBudget;
import io.github.claw4j.agent.schedule.ScheduleTools;
import io.github.claw4j.agent.observability.SpanTracer;
import io.github.claw4j.agent.persona.AgentProperties;
import io.github.claw4j.domain.port.Approvals;
import io.github.claw4j.agent.approval.ApprovalRequiredTools;
import io.github.claw4j.agent.subagent.SubagentAutoConfiguration;
import io.github.claw4j.agent.subagent.SubagentCatalog;
import io.github.claw4j.agent.subagent.SubagentDefinition;
import io.github.claw4j.agent.subagent.SubagentDelegation;
import io.github.claw4j.agent.subagent.SubagentRunner;
import io.github.claw4j.agent.subagent.SubagentTaskRepository;
import io.github.claw4j.capability.untrusted.UntrustedToolCallback;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPolicyPipeline;
import io.github.claw4j.domain.principal.ToolProfile;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springaicommunity.agent.tools.task.TaskOutputTool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 子 Agent 委派：目录里有哪些专职 Agent、派出去时带什么边界、子那边用的还是不是父的身份。
 *
 * <p>配置错误在启动时暴露（目录空、定义缺字段、白名单点名不存在的工具）；
 * 运行期最贵的一条是"委派提权"——它不报错，只是子 Agent 看见了父看不见的表。
 */
class SubagentDelegationTest {

    private static final String BODY = "你是销售分析员。先查数，再给结论。";

    @TempDir
    Path directory;

    /** 每一份夹具单独一个子目录：同一个 @TempDir 里重名会被后写的覆盖。 */
    private final AtomicInteger fixtureCounter = new AtomicInteger();

    /**
     * P09 的父子一致性：子 Agent 的工具集来自与父同一个收集方法、同一份档位策略。
     *
     * <p>这一条盯的是一种很具体的失效：如果子那一侧用了不过滤的默认值，一个 {@code messaging}
     * 部署只要让模型派个活，就能让子 Agent 把 {@code run_script} 拿到手，父那边挡得再严也没用。
     * 它不报错、日志上看不出来，只表现为「说好了不许跑脚本，结果跑了」。
     */
    @Test
    @DisplayName("子 Agent 拿到的也是筛过的清单：messaging 部署里它没有 run_script（P09）")
    void subagentToolsRespectTheDeploymentProfile() {
        List<ToolCallback> tools = SubagentAutoConfiguration.subagentToolCallbacks(
                providerOf(List.of(new RecordingTool("run_script", "跑了"),
                        new RecordingTool("querySales", "128400"))),
                providerOf(List.of()),
                null,
                new ToolsProperties(ToolTier.MESSAGING, List.of(), List.of(), List.of(), null, null, null),
                List.of(ToolTierRegistry.sameFor(Set.of("run_script"), ToolTier.CODING),
                        ToolTierRegistry.sameFor(Set.of("querySales"), ToolTier.MESSAGING)));

        assertThat(tools)
                .extracting(callback -> callback.getToolDefinition().name())
                .as("父子两处必须用同一份档位策略，否则派活就成了绕过档位的口子")
                .containsExactly("querySales");
    }

    @Test
    @DisplayName("子 Agent 拿不到定时任务：它是一次性执行体，任务表是全局的，谁认领不成立")
    void subagentCannotSchedule() {
        List<ToolCallback> tools = SubagentAutoConfiguration.subagentToolCallbacks(
                providerOf(List.of(new RecordingTool("querySales", "128400"),
                        new RecordingTool(ScheduleTools.CREATE, "ok"),
                        new RecordingTool(ScheduleTools.LIST, "[]"))),
                providerOf(List.of()),
                null,
                ToolsProperties.defaults(),
                List.of(ToolTierRegistry.allProfilesFor(
                        Set.of("querySales", ScheduleTools.CREATE, ScheduleTools.LIST))));

        assertThat(tools)
                .extracting(callback -> callback.getToolDefinition().name())
                .as("角色清单是结构约束，不是配置问题：全默认配置下也必须减掉")
                .containsExactly("querySales");
    }

    @Test
    @DisplayName("显式白名单点名的定时任务工具能留下：配置比角色的默认更懂这个部署")
    void explicitAllowOverridesSubagentRoleDeny() {
        List<ToolCallback> tools = SubagentAutoConfiguration.subagentToolCallbacks(
                providerOf(List.of(new RecordingTool(ScheduleTools.LIST, "[]"))),
                providerOf(List.of()),
                null,
                new ToolsProperties(ToolTier.FULL, List.of(), List.of(), List.of(), null, null,
                        new ToolsProperties.SubagentTools(List.of(ScheduleTools.LIST), List.of())),
                List.of(ToolTierRegistry.allProfilesFor(Set.of(ScheduleTools.LIST))));

        assertThat(tools)
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly(ScheduleTools.LIST);
    }

    /**
     * 只要一个可枚举的 {@code ObjectProvider}：收集方法只用到 {@code orderedStream()}。
     * 其余入口在这里不该被走到——走到就说明装配依赖了"单例"语义，而那正是要避免的。
     */
    private static <T> ObjectProvider<T> providerOf(List<T> items) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                throw new UnsupportedOperationException("清单收集只走 orderedStream");
            }

            @Override
            public Stream<T> stream() {
                return items.stream();
            }
        };
    }

    @Test
    @DisplayName("目录按文件名排序读，非 md 文件不进清单")
    void catalogReadsSortedMarkdownAndIgnoresOtherFiles() throws IOException {
        write("b.md", definition("beta", "第二个", "lookup", ""));
        write("a.md", definition("alpha", "第一个", "lookup", ""));
        write("notes.txt", "不是定义");

        List<SubagentDefinition> loaded = SubagentCatalog.load(directory);

        assertThat(loaded).extracting(SubagentDefinition::name).containsExactly("alpha", "beta");
    }

    @Test
    @DisplayName("配了目录却没有可用定义就启动失败：空目录不是「这一层不存在」")
    void configuredDirectoryWithoutDefinitionsFails() throws IOException {
        assertThatThrownBy(() -> SubagentCatalog.load(directory.resolve("missing")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在");

        Path empty = Files.createDirectory(directory.resolve("empty"));
        assertThatThrownBy(() -> SubagentCatalog.load(empty))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("一个 *.md 都没有");
    }

    @Test
    @DisplayName("一份定义写坏就整层起不来，不静默跳过它")
    void brokenDefinitionFailsTheWholeLayer() throws IOException {
        write("good.md", definition("good", "好的", "lookup", ""));
        write("bad.md", """
                ---
                name: bad
                ---

                没有 description
                """);

        assertThatThrownBy(() -> SubagentCatalog.load(directory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("description");
    }

    @Test
    @DisplayName("front matter 的工具名按逗号拆开，正文原样成为系统提示词")
    void parseSplitsToolsAndKeepsBody() throws IOException {
        Path file = write("a.md", definition("alpha", "管销售", "lookup, querySales", "sendEmail"));

        SubagentDefinition definition = SubagentDefinition.parse(file);

        assertThat(definition.name()).isEqualTo("alpha");
        assertThat(definition.description()).isEqualTo("管销售");
        assertThat(definition.tools()).containsExactly("lookup", "querySales");
        assertThat(definition.disallowedTools()).containsExactly("sendEmail");
        assertThat(definition.systemPrompt()).isEqualTo(BODY);
    }

    @Test
    @DisplayName("缺 description 的定义直接拒绝：父 Agent 只靠这一行决定派不派它")
    void definitionWithoutDescriptionIsRejected() {
        assertThatThrownBy(() -> new SubagentDefinition("x", " ", BODY, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    @DisplayName("白名单写 Task 按定义错误拒绝：委派不嵌套")
    void whitelistNamingTaskIsRejected() {
        assertThatThrownBy(() -> new SubagentDefinition(
                "nested", "想再派一层", BODY, List.of(SubagentDelegation.TOOL_NAME), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SubagentDelegation.TOOL_NAME)
                .hasMessageContaining("不嵌套");
    }

    @Test
    @DisplayName("白名单点名了工具但当前没有它：拒绝并点名缺哪个，不悄悄少给")
    void whitelistNameMustExistInTheAvailableTools() {
        SubagentDefinition definition = new SubagentDefinition(
                "alpha", "管销售", BODY, List.of("missingTool"), List.of());

        assertThatThrownBy(() -> definition.selectTools(List.of(new RecordingTool("lookup", "1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missingTool");
    }

    @Test
    @DisplayName("没写白名单时继承全部，但委派工具自己一定被减掉")
    void inheritedToolsNeverIncludeDelegationItself() {
        SubagentDefinition definition = new SubagentDefinition(
                "alpha", "管销售", BODY, List.of(), List.of());
        RecordingTool lookup = new RecordingTool("lookup", "1");
        RecordingTool task = new RecordingTool(SubagentDelegation.TOOL_NAME, "递归");

        List<String> names = definition.selectTools(List.of(lookup, task)).stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();

        assertThat(names).containsExactly("lookup");
    }

    @Test
    @DisplayName("黑白名单算完一个工具都不剩就拒绝：没有工具的子 Agent 只能靠猜")
    void emptySelectionIsRejected() {
        SubagentDefinition definition = new SubagentDefinition(
                "alpha", "管销售", BODY, List.of(), List.of("lookup"));

        assertThatThrownBy(() -> definition.selectTools(List.of(new RecordingTool("lookup", "1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("一个可用工具都没有");
    }

    @Test
    @DisplayName("委派没带身份一律拒绝，子 Agent 一次都不启动")
    void delegationWithoutPrincipalIsDenied() {
        FixedChatModel model = new FixedChatModel("不该被调用");
        SubagentRunner runner = runner(model, List.of());
        SubagentDelegation delegation = delegation(List.of(definitionObject("alpha", "lookup")), runner);

        String answer = delegation.call("{\"subagent_type\":\"alpha\",\"prompt\":\"查数\"}");

        assertThat(answer).startsWith(SubagentDelegation.DENIAL_PREFIX);
        assertThat(model.prompts()).isEmpty();
    }

    @Test
    @DisplayName("参数对不上时不派出去，并告诉模型「派谁、做什么」两样都要")
    void malformedOrUnknownDelegationIsRejectedWithGuidance() {
        FixedChatModel model = new FixedChatModel("不该被调用");
        SubagentDelegation delegation = delegation(
                List.of(definitionObject("alpha", "lookup")),
                runner(model, List.of()));
        ToolContext identity = new ToolContext(
                Map.of(ContextKeys.PRINCIPAL, new Principal("u-1")));

        // 名字不在清单里：这句话现在由库说（它按 subagent_type 找定义），要点名那个名字。
        assertThatThrownBy(() -> delegation.call(
                "{\"subagent_type\":\"nobody\",\"prompt\":\"查数\"}", identity))
                .hasMessageContaining("nobody");
        // 没写 prompt：库的结构允许可空，所以要在这里挡住——子 Agent 看不到父的对话，空 prompt 等于让它猜。
        assertThat(delegation.call("{\"subagent_type\":\"alpha\"}", identity))
                .contains("prompt");
        assertThat(model.prompts()).isEmpty();
    }

    @Test
    @DisplayName("成功委派只回传结论：子窗口里的工具明细不回到父，父的问答也不进子")
    void delegationReturnsOnlyTheConclusion() {
        RecordingTool lookup = new RecordingTool("lookup", "原始行 128400");
        AtomicInteger childCalls = new AtomicInteger();
        FixedChatModel child = new FixedChatModel(
                prompt -> switch (childCalls.getAndIncrement()) {
                    // 子那一侧也是"先检索、再调用"：它看得见的工具与父一样是渐进披露的
                    case 0 -> ChatPipelineTest.requestTool(ToolSearch.TOOL_NAME, "{\"query\":\"lookup\"}");
                    case 1 -> ChatPipelineTest.requestTool("lookup", "{}");
                    default -> ChatPipelineTest.answer("结论：128400");
                },
                List.of());
        SubagentDelegation delegation = delegation(List.of(definitionObject("alpha", "lookup")),
                runner(child, List.of(lookup)));
        ToolContext identity = new ToolContext(
                Map.of(ContextKeys.PRINCIPAL, new Principal("u-1")));

        String result = delegation.call("{\"subagent_type\":\"alpha\",\"prompt\":\"上周华东成交额\"}", identity);

        // 结论按"数据块"回来：它是供父综合的报告，不是新的任务（票 01 的子 Agent 出口）。
        assertThat(result)
                .contains("结论：128400")
                .contains("<prompt-data>")
                .contains("Subagent result: alpha")
                .doesNotContain("原始行");
        assertThat(child.prompts())
                .as("三次模型调用：检索 → 查到工具后调用 → 给结论；中间两轮的明细都不回父")
                .hasSize(3);
        String childPrompt = child.prompts().getFirst().getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(childPrompt).contains("上周华东成交额").contains(BODY);
        assertThat(delegation.getToolDefinition().description())
                .contains("alpha")
                .contains("管销售")
                .doesNotContain("先查数");
    }

    /**
     * 收集是幂等的：已经包过的工具不再包第二层（票 19）。
     *
     * <p>子链与父链走同一条收集路径，但两边拿到的输入不同——提供给子链的那个
     * {@code ToolCallbackProvider} 交出来的工具本身已经带着一层包装。于是同一次工具调用被写两遍
     * 审计，第二遍被唯一键挡住（落在 {@code duplicates} 上）。这一条钉的就是"收集不该改层数"。
     */
    @Test
    @DisplayName("收集是幂等的：已经包过的工具不再包第二层（票 19）")
    void collectToolCallbacksDoesNotWrapAnAlreadyMeteredTool() {
        ToolCallAudit audit = new ToolCallAudit(event -> { });
        ToolProfile profile = ToolsProperties.defaults().toProfile();
        List<ToolTierRegistry> tiers = List.of(ToolTierRegistry.sameFor(Set.of("lookup"), ToolTier.MESSAGING));
        List<ToolCallback> once = AgentAutoConfiguration.collectToolCallbacks(
                providerOf(List.of(new RecordingTool("lookup", "1"))), providerOf(List.of()),
                null, audit, SpanTracer.NONE, ObservationRegistry.NOOP, profile,
                tiers, ToolPolicyPipeline.none(), selection -> { });
        List<ToolCallback> twice = AgentAutoConfiguration.collectToolCallbacks(
                providerOf(once), providerOf(List.of()),
                null, audit, SpanTracer.NONE, ObservationRegistry.NOOP, profile,
                tiers, ToolPolicyPipeline.none(), selection -> { });

        assertThat(twice).hasSize(1);
        assertThat(twice.getFirst())
                .as("再收集一次不该再造一个包装：那会让同一次调用写两遍审计")
                .isSameAs(once.getFirst());

        // 线上那条路径还多一层净化器：子链拿到的 provider 交出来的是 Sanitizing(Metered(…))。
        // 只看最外层类型会漏，所以要穿透装饰器判。
        List<ToolCallback> sanitized = providerOf(List.of(
                UntrustedToolCallback.sanitizing(once.getFirst()))).orderedStream().toList();
        List<ToolCallback> again = AgentAutoConfiguration.collectToolCallbacks(
                providerOf(sanitized), providerOf(List.of()),
                null, audit, SpanTracer.NONE, ObservationRegistry.NOOP, profile,
                tiers, ToolPolicyPipeline.none(), selection -> { });
        assertThat(again.getFirst())
                .as("净化器里面已经有计量：再包一层就是两遍审计")
                .isSameAs(sanitized.getFirst());
    }

    /**
     * 子 Agent 的工具调用落在**自己那一轮**上（ADR-0037 票 08）。
     *
     * <p>这一条盯的是一种三样都缺、却都不报错的状态：审计没接（一行都没有）、接了但没给 executionId
     * （账本直接跳过没有 run 的事件）、给了 executionId 但归属还是父的（按 agent 查会查到父头上）。
     */
    @Test
    @DisplayName("子 Agent 的工具调用落在自己那一轮：两条运行行 + 工具行都是同一个 executionId（票 08）")
    void subagentToolCallsAreAuditedInTheirOwnRun() {
        List<AuditEvent> events = new ArrayList<>();
        RecordingTool lookup = new RecordingTool("lookup", "原始行 128400");
        AtomicInteger childCalls = new AtomicInteger();
        FixedChatModel child = new FixedChatModel(
                prompt -> switch (childCalls.getAndIncrement()) {
                    case 0 -> ChatPipelineTest.requestTool(ToolSearch.TOOL_NAME, "{\"query\":\"lookup\"}");
                    case 1 -> ChatPipelineTest.requestTool("lookup", "{}");
                    default -> ChatPipelineTest.answer("结论：128400");
                }, List.of());
        // 工具走线上那条收集路径（不然拿到的是没被包过的桩，审计根本不会发生）：
        // 这一条要验的正是"子链的工具被包上了、并且写到了子那一轮"。
        ToolCallAudit audit = new ToolCallAudit(events::add);
        List<ToolCallback> tools = SubagentAutoConfiguration.subagentToolCallbacks(
                providerOf(List.of(lookup)), providerOf(List.of()),
                null,
                ToolsProperties.defaults(),
                List.of(ToolTierRegistry.sameFor(Set.of("lookup"), ToolTier.MESSAGING)),
                null, ApprovalRequiredTools.none(), audit, ObservationRegistry.NOOP);
        SubagentDelegation delegation = delegation(List.of(definitionObject("alpha", "lookup")),
                runner(child, tools, audit));
        ToolContext identity = new ToolContext(Map.of(ContextKeys.PRINCIPAL, new Principal("u-1")));

        delegation.call("{\"subagent_type\":\"alpha\",\"prompt\":\"上周华东成交额\"}", identity);

        List<AuditEvent> runEvents = events.stream()
                .filter(event -> event.action() == AuditAction.AGENT_RUN_STARTED
                        || event.action() == AuditAction.AGENT_RUN_FINISHED)
                .toList();
        assertThat(runEvents)
                .as("一次委派是自己的一轮：开始、结束各一条")
                .hasSize(2);
        assertThat(runEvents).extracting(AuditEvent::status)
                .containsExactly(AuditStatus.STARTED, AuditStatus.SUCCEEDED);
        AuditEvent.Scope scope = runEvents.getFirst().scope();
        assertThat(scope.executionId()).as("子那一轮有自己的编号，不与父共享").isNotBlank();
        assertThat(scope.sessionKey()).isEqualTo("subagent:alpha");
        assertThat(scope.agentId()).as("干活的是子 Agent，不是父那个部署级 agent").isEqualTo("alpha");
        assertThat(scope.requestedBy()).as("委派不提权：人还是那个人").isEqualTo("u-1");

        List<AuditEvent> lookupEvents = events.stream()
                .filter(event -> "lookup".equals(event.toolName()))
                .toList();
        assertThat(lookupEvents)
                .as("子链调了 lookup：开始与结束各一条（没有 executionId 时账本会整条跳过）")
                .hasSize(2);
        assertThat(lookupEvents).extracting(AuditEvent::status)
                .containsExactly(AuditStatus.STARTED, AuditStatus.SUCCEEDED);
        assertThat(lookupEvents).allSatisfy(event -> {
            assertThat(event.scope().executionId()).as("工具行挂在这个子 run 上").isEqualTo(scope.executionId());
            assertThat(event.scope().agentId()).isEqualTo("alpha");
            assertThat(event.scope().sessionKey()).isEqualTo("subagent:alpha");
        });
    }

    /**
     * 子 Agent 挂了，结束那一条要写清楚（票 08）。
     *
     * <p>只写开始不写结束的话，30 分钟后它会被孤儿扫尾补成 {@code unknown}——那是"不知道结果"，
     * 而这里明明知道。归一用父链那套判据（超时 / 取消 / 普通失败），不另写一份。
     */
    @Test
    @DisplayName("子 Agent 挂了也留一条 failed 的结束行，executionId 与开始那条相同（票 08）")
    void subagentRunFailureIsRecorded() {
        List<AuditEvent> events = new ArrayList<>();
        FixedChatModel child = new FixedChatModel(prompt -> {
            throw new IllegalStateException("模型挂了");
        }, List.of());
        SubagentRunner runner = runner(child, List.of(new RecordingTool("lookup", "1")),
                new ToolCallAudit(events::add));

        assertThatThrownBy(() -> runner.delegate(definitionObject("alpha", "lookup"), "查数",
                new Principal("u-1")))
                .as("模型挂了要往上抛，不能被吞成一句「没有结论」")
                .isInstanceOf(RuntimeException.class);

        assertThat(events).extracting(AuditEvent::action)
                .containsExactly(AuditAction.AGENT_RUN_STARTED, AuditAction.AGENT_RUN_FINISHED);
        AuditEvent finished = events.getLast();
        assertThat(finished.status()).isEqualTo(AuditStatus.FAILED);
        assertThat(finished.errorCode()).as("与父链同一套归一：普通失败记 run_failed")
                .isEqualTo(AuditErrorCode.RUN_FAILED);
        assertThat(finished.scope().executionId()).isEqualTo(events.getFirst().scope().executionId());
    }

    @Test
    @DisplayName("工具形状来自库：参数是 TaskCall 那一组，描述里的注册表由库拼")
    void toolShapeComesFromTheLibrary() {
        SubagentDelegation delegation = delegation(List.of(definitionObject("alpha", "lookup")),
                runner(new FixedChatModel("好"), List.of()));

        String schema = delegation.getToolDefinition().inputSchema();
        assertThat(schema)
                .as("库的 TaskCall：subagent_type / prompt / run_in_background 都在")
                .contains("subagent_type")
                .contains("prompt")
                .contains("run_in_background")
                .as("我们原来那套 subagent / task 参数不该还在")
                .doesNotContain("\"subagent\"");
        assertThat(delegation.getToolDefinition().description())
                .contains("alpha")
                .contains("管销售")
                .doesNotContain("先查数");
    }

    @Test
    @DisplayName("后台派出去也带着父的身份：身份在提交那一刻被搬进后台线程")
    void backgroundTaskKeepsTheParentIdentity() {
        FixedChatModel child = new FixedChatModel("结论：后台跑完了");
        SubagentTaskRepository repository = new SubagentTaskRepository();
        SubagentDelegation delegation = delegation(List.of(definitionObject("alpha", "lookup")),
                runner(child, List.of(new RecordingTool("lookup", "1"))), repository);
        ToolCallback output = TaskOutputTool.builder().taskRepository(repository).build();
        ToolContext identity = new ToolContext(
                Map.of(ContextKeys.PRINCIPAL, new Principal("u-1")));

        String dispatched = delegation.call(
                "{\"subagent_type\":\"alpha\",\"prompt\":\"查数\",\"run_in_background\":true}",
                identity);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^task_id: (\\S+)").matcher(dispatched);
        assertThat(matcher.find()).as("库回的第一行就是 task_id：%s", dispatched).isTrue();
        String taskId = matcher.group(1);
        String result = output.call("{\"task_id\":\"" + taskId + "\",\"block\":true}", identity);

        assertThat(dispatched).as("立刻回一个 id，不等人").contains("TaskOutput");
        assertThat(result)
                .as("身份在后台线程里丢了的话，执行器会回「没有权限」，这条就红")
                .contains("后台跑完了");
        repository.shutdown();
    }

    @Test
    @DisplayName("子 Agent 什么都没说时回一个明确的占位，不让父以为拿到了空结论")
    void emptyConclusionHasExplicitPlaceholder() {
        FixedChatModel child = new FixedChatModel("");
        SubagentDelegation delegation = delegation(List.of(definitionObject("alpha", "lookup")),
                runner(child, List.of(new RecordingTool("lookup", "1"))));
        ToolContext identity = new ToolContext(
                Map.of(ContextKeys.PRINCIPAL, new Principal("u-1")));

        String result = delegation.call("{\"subagent_type\":\"alpha\",\"prompt\":\"查数\"}", identity);

        assertThat(result).contains("没有给出结论");
    }

    @Test
    @DisplayName("装配把 Task 交进工具清单：它与别的工具走同一条收集路径")
    void assemblyRegistersTheTaskTool() throws IOException {
        write("alpha.md", definition("alpha", "管销售", "lookup", ""));
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("lookup", new RecordingTool("lookup", "128400"));
        SubagentAutoConfiguration configuration = new SubagentAutoConfiguration();
        // 两个工具的档位都要先登记（P09）：装配期会问清单里每一个工具属于哪一档，
        // 容器里少一条就是启动失败。这里用默认的全档，清单内容只取决于注册了哪些工具。
        beans.registerSingleton("lookupTier", ToolTierRegistry.sameFor(List.of("lookup"), ToolTier.MESSAGING));
        beans.registerSingleton("taskTier", configuration.subagentTierRegistry());

        ToolCallback task = configuration.subagentToolCallback(
                directory.toString(),
                new FixedChatModel("好"),
                beans.getBeanProvider(ToolCallback.class),
                beans.getBeanProvider(ToolCallbackProvider.class),
                beans.getBeanProvider(ObservationRegistry.class),
                beans.getBeanProvider(MeterRegistry.class),
                beans.getBeanProvider(ToolTierRegistry.class),
                ToolsProperties.defaults(),
                new LuceneToolIndex(ToolSearchProperties.Lucene.DEFAULT_MIN_SCORE),
                ToolSearchProperties.defaults(),
                AgentProperties.defaults(),
                beans.getBeanProvider(Approvals.class),
                beans.getBeanProvider(ApprovalRequiredTools.class),
                // 审计：这个容器里没有持久化那一层，于是执行器拿到 null——裁剪部署的常态（票 08）
                beans.getBeanProvider(ToolCallAudit.class),
                new SubagentTaskRepository());

        assertThat(task.getToolDefinition().name()).isEqualTo(SubagentDelegation.TOOL_NAME);
        assertThat(task.getToolDefinition().description()).contains("alpha").doesNotContain("先查数");

        beans.registerSingleton("task", task);
        assertThat(AgentAutoConfiguration.collectToolCallbacks(
                beans.getBeanProvider(ToolCallback.class),
                beans.getBeanProvider(ToolCallbackProvider.class),
                beans.getBeanProvider(MeterRegistry.class).getIfAvailable(),
                // 审计：这一条只关心清单里有什么（P07），不是"谁批的"
                null,
                // trace 也同理：这一条问的是清单，不是 span（P14）
                SpanTracer.NONE,
                // 调用编号同理：这一条不验观测（票 06）
                ObservationRegistry.NOOP,
                // 档位用默认（full）：这条验的是"两种形态都被收到"，不是"怎么筛"
                ToolsProperties.defaults().toProfile(),
                beans.getBeanProvider(ToolTierRegistry.class).orderedStream().toList()))
                .extracting(callback -> callback.getToolDefinition().name())
                .contains("lookup", SubagentDelegation.TOOL_NAME);

        // 权限登记那一层已经下线（票 04）：这个工具"能不能被看见"由画像表回答，
        // "子 Agent 能做什么"由它自己那份清单回答，两者都不需要一张权限声明。
    }

    @Test
    @DisplayName("tools 写成逐行列表、逗号分隔、方括号三种形态，挑出来的工具集一致")
    void toolListFormsParseToTheSameSelection() throws IOException {
        Path asList = writeListDefinition("list.md", "tools:", List.of("- querySales", "- lookup"));
        Path asComma = write("comma.md", definition("alpha", "管销售", "querySales, lookup", ""));
        Path asFlow = write("flow.md", definition("alpha", "管销售", "[querySales, lookup]", ""));

        List<String> expected = List.of("querySales", "lookup");
        assertThat(SubagentDefinition.parse(asList).tools()).isEqualTo(expected);
        assertThat(SubagentDefinition.parse(asComma).tools()).isEqualTo(expected);
        assertThat(SubagentDefinition.parse(asFlow).tools()).isEqualTo(expected);
    }

    @Test
    @DisplayName("写了 tools 却解析不出名字：按定义错误失败，绝不当成「继承全部」")
    void declaredButEmptyToolSectionIsRejected() throws IOException {
        Path emptyWhitelist = write("empty.md",
                "---\nname: alpha\ndescription: 管销售\ntools:\n---\n\n" + BODY + "\n");
        Path emptyBlacklist = write("empty-black.md",
                "---\nname: alpha\ndescription: 管销售\ndisallowedTools:\n---\n\n" + BODY + "\n");

        assertThatThrownBy(() -> SubagentDefinition.parse(emptyWhitelist))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tools")
                .hasMessageContaining("解析不出任何工具名");
        assertThatThrownBy(() -> SubagentDefinition.parse(emptyBlacklist))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disallowedTools")
                .hasMessageContaining("解析不出任何工具名");
    }

    @Test
    @DisplayName("示例里的复核员只拿得到 querySales：发邮件与跑脚本不在它的白名单里")
    void exampleReviewerOnlySeesItsOwnWhitelist() {
        SubagentDefinition definition = SubagentDefinition.parse(
                repositoryFile("examples/subagents/sql-reviewer.md"));
        List<ToolCallback> available = List.of(
                new RecordingTool("querySales", "128400"),
                new RecordingTool("mail_send", "发出去了"),
                new RecordingTool("run_script", "跑了"));

        assertThat(definition.tools()).containsExactly("querySales");
        assertThat(definition.selectTools(available))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("querySales");
    }

    private SubagentRunner runner(ChatModel model, List<ToolCallback> tools) {
        return runner(model, tools, null);
    }

    /**
     * 带审计的执行器。
     *
     * @param model 子链用的模型
     * @param tools 子链能用的工具
     * @param audit 审计接缝；{@code null} 表示这个部署没装（裁剪部署那一条要它）
     * @return 执行器
     */
    private SubagentRunner runner(ChatModel model, List<ToolCallback> tools, ToolCallAudit audit) {
        return new SubagentRunner(model, ObservationRegistry.NOOP, audit, () -> tools,
                new LuceneToolIndex(ToolSearchProperties.Lucene.DEFAULT_MIN_SCORE),
                // 显式 always：这几条要验的是子链的委派与审计，脚本写的就是「检索 → 调用 → 结论」
                // 三步。默认 auto 在工具只有一两个时会全塞，那三步就少一步，脚本对不上——而
                // "子链按默认配置怎么滑档"不是这里的题目（那条在 ModelAndToolSelectionTest）。
                new ToolSearchProperties(null, 5, null, ToolSearchProperties.Mode.ALWAYS, 0, null, null, null, null, null),
                ToolCallBudget.MAX_TOTAL_CALLS);
    }

    /**
     * 按线上的装配路径建出委派工具：定义先落成文件，再由库的 {@code TaskTool} 从引用读回来。
     *
     * <p>不直接 new 一份定义列表塞进去——那验的是"我要它长什么样"，不是"库里长什么样"。
     */
    private SubagentDelegation delegation(List<SubagentDefinition> definitions, SubagentRunner runner) {
        return delegation(definitions, runner, new SubagentTaskRepository());
    }

    private SubagentDelegation delegation(List<SubagentDefinition> definitions, SubagentRunner runner,
                                         SubagentTaskRepository repository) {
        Path fixtures = directory.resolve("fixtures-" + fixtureCounter.incrementAndGet());
        try {
            Files.createDirectories(fixtures);
            for (SubagentDefinition definition : definitions) {
                Files.writeString(fixtures.resolve(definition.name() + ".md"), markdownOf(definition));
            }
        }
        catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return (SubagentDelegation) SubagentAutoConfiguration.delegationTool(
                fixtures, runner, repository);
    }

    /** 一份内存里的定义写回成定义文件，好让库去解析它。 */
    private static String markdownOf(SubagentDefinition definition) {
        StringBuilder front = new StringBuilder("---\n")
                .append("name: ").append(definition.name()).append('\n')
                .append("description: ").append(definition.description()).append('\n');
        if (!definition.tools().isEmpty()) {
            front.append("tools: ").append(String.join(", ", definition.tools())).append('\n');
        }
        if (!definition.disallowedTools().isEmpty()) {
            front.append("disallowedTools: ")
                    .append(String.join(", ", definition.disallowedTools())).append('\n');
        }
        return front.append("---\n\n").append(definition.systemPrompt()).append('\n').toString();
    }

    private static SubagentDefinition definitionObject(String name, String tool) {
        return new SubagentDefinition(name, "管销售", BODY, List.of(tool), List.of());
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(directory.resolve(name), content);
    }

    private static String definition(String name, String description, String tools, String disallowed) {
        StringBuilder front = new StringBuilder("---\n")
                .append("name: ").append(name).append('\n')
                .append("description: ").append(description).append('\n');
        if (!tools.isBlank()) {
            front.append("tools: ").append(tools).append('\n');
        }
        if (!disallowed.isBlank()) {
            front.append("disallowedTools: ").append(disallowed).append('\n');
        }
        return front.append("---\n\n").append(BODY).append('\n').toString();
    }

    private Path writeListDefinition(String fileName, String toolsHeader, List<String> itemLines) throws IOException {
        StringBuilder front = new StringBuilder("---\nname: alpha\ndescription: 管销售\n")
                .append(toolsHeader).append('\n');
        itemLines.forEach(line -> front.append("  ").append(line).append('\n'));
        return write(fileName, front.append("---\n\n").append(BODY).append('\n').toString());
    }

    /** 从模块目录往上找仓库里的文件：surefire 的工作目录是模块，IDEA 里可能是仓库根。 */
    private static Path repositoryFile(String relative) {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("从 " + System.getProperty("user.dir") + " 往上找不到 " + relative);
    }
}
