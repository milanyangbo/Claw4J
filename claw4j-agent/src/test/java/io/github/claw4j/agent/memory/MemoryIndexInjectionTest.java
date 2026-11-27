package io.github.claw4j.agent.memory;

import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 票 32：长期记忆的索引**直接进提示词**，不再要求模型先调一次 {@code MemoryView}。
 *
 * <p>照 OpenClaw 的分层：{@code MEMORY.md} 是每次开场都要看的，所以它是内容；工具留给
 * "索引之外的文件"和写入。省下的那次调用是每个会话的第一次往返——而且它原来还是个失败点：
 * 工具被披露层抹掉时，提示词却还在点它的名，模型一伸手就 500。
 */
class MemoryIndexInjectionTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("索引内容进了系统提示，提示词里不再要求先调 MemoryView")
    void indexIsInjectedAndTheFirstCallIsNoLongerDemanded() throws IOException {
        write("u-32", "- 用户偏好简洁\n- 项目文档用中文\n");

        ChatClientRequest out = new PrincipalMemoryAdvisor(root).before(request("u-32"), null);

        assertThat(out.prompt().getSystemMessage().getText())
                .as("记忆内容要能直接被读到")
                .contains("用户偏好简洁", "项目文档用中文")
                .as("库那句「先用 MEMORY.md」是这次要去掉的东西")
                .doesNotContain("as the first call in any session");
    }

    @Test
    @DisplayName("注入的工具名登记进上下文：6 个记忆工具，不需要硬编码")
    void injectedToolNamesAreRegistered() throws IOException {
        write("u-32", "- 一条记忆\n");

        ChatClientRequest out = new PrincipalMemoryAdvisor(root).before(request("u-32"), null);

        assertThat(out.context().get(ContextKeys.INJECTED_TOOLS))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.COLLECTION)
                .contains("MemoryView", "MemoryCreate", "MemoryStrReplace",
                        "MemoryInsert", "MemoryDelete", "MemoryRename");    }

    @Test
    @DisplayName("索引超长时只注入前一段，并说明全文还在磁盘上")
    void oversizedIndexIsTruncatedNotDropped() throws IOException {
        write("u-32", "长".repeat(7000));

        ChatClientRequest out = new PrincipalMemoryAdvisor(root).before(request("u-32"), null);

        String system = out.prompt().getSystemMessage().getText();
        assertThat(system)
                .as("截断要留标记：模型得知道还有更多，也知道怎么取")
                .contains("已截断", "MemoryView");
        assertThat(system.length())
                .as("整段系统提示不该被一份过大的索引撑爆")
                .isLessThan(12_000);
    }

    @Test
    @DisplayName("第一次对话（还没有 MEMORY.md）也要说清楚，而不是留一段空白")
    void firstConversationSaysThereIsNothingYet() {
        ChatClientRequest out = new PrincipalMemoryAdvisor(root).before(request("brand-new"), null);

        assertThat(out.prompt().getSystemMessage().getText()).contains("还没有任何记忆");
    }

    private void write(String userId, String content) throws IOException {
        Path directory = MemoryDirectories.under(root, userId);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("MEMORY.md"), content);
    }

    private static ChatClientRequest request(String userId) {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of())
                .build();
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("你是助手"), new UserMessage("上周华东成交额")), options);
        Map<String, Object> context = new HashMap<>();
        context.put(ContextKeys.PRINCIPAL, new Principal(userId));
        return new ChatClientRequest(prompt, context);
    }
}
