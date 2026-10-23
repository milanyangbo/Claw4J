package io.github.claw4j.agent.model;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 脚本化假模型自己的行为。它是测试夹具，但它有两个性质必须钉住：
 * 顺序吐响应（否则"模型先要调工具、再据此作答"这两步测不出来），
 * 以及脚本耗尽时明确失败（静默返回空会让工具循环变成无限循环或者假绿）。
 */
class ScriptedChatModelTest {

    @Test
    void 按脚本顺序吐响应() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .thenText("第一轮")
                .thenText("第二轮")
                .build();

        assertThat(model.call(new Prompt("问题一")).getResult().getOutput().getText()).isEqualTo("第一轮");
        assertThat(model.call(new Prompt("问题二")).getResult().getOutput().getText()).isEqualTo("第二轮");
        assertThat(model.remaining()).isZero();
    }

    @Test
    void 脚本耗尽时明确失败而不是静默返回空() {
        ScriptedChatModel model = ScriptedChatModel.builder().thenText("只有这一条").build();
        model.call(new Prompt("第一次"));

        assertThatThrownBy(() -> model.call(new Prompt("第二次")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("脚本已耗尽");
    }

    @Test
    void 记录每一次收到的提示_用来断言工具结果回到了模型() {
        ScriptedChatModel model = ScriptedChatModel.builder().thenText("回答").build();

        model.call(new Prompt("第一个问题"));

        assertThat(model.promptTexts()).containsExactly("第一个问题");
    }

    @Test
    void 流式路径按块吐且只消耗一条脚本() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .thenText("这是一段足够长的内容用来验证分块")
                .build();

        List<String> chunks = model.stream(new Prompt("问题"))
                .map(ScriptedChatModel::textOf)
                .collectList()
                .block();

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(String.join("", chunks)).isEqualTo("这是一段足够长的内容用来验证分块");
        assertThat(model.remaining()).isZero();
    }

    /**
     * 工具调用那一轮的模型响应：正文为空、只带工具调用请求。
     * 这个形状是后面端到端断言的基础——如果工具没被真的调用，
     * 循环就不会有第二轮，最终回答就会是这一轮的**空字符串**，一眼可辨。
     */
    @Test
    void 工具调用那一轮的响应没有正文() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .thenToolCall("querySales", "{\"region\":\"华东\"}")
                .build();

        var response = model.call(new Prompt("上周华东区成交额多少"));

        assertThat(response.getResult().getOutput().getText()).isEmpty();
        assertThat(response.getResult().getOutput().hasToolCalls()).isTrue();
    }
}
