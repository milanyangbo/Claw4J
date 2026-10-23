package io.github.claw4j.agent.model;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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

        // 断言确切的块边界，不是"多于一块"——后者在 17 字符切 2 块时也通过，
        // 那样"流式被降级成一次性响应"就探测不出来了（这条保护是 M0 特意留下的）
        assertThat(chunks).containsExactly("这是一段足够长的", "内容用来验证分块");
        assertThat(model.remaining()).isZero();
    }

    /**
     * 脚本耗尽在流式上要以 {@code onError} 传出来，而不是在拼装流的那一行同步抛出。
     * 真实模型不会在 stream() 里抛，混在一起会让"流上的失败"被误读成调用方的错。
     */
    @Test
    void 脚本耗尽在流式上走onError而不是同步抛出() {
        ScriptedChatModel model = ScriptedChatModel.builder().thenText("只有这一条").build();
        model.stream(new Prompt("第一次")).blockLast();

        // 前半句是关键：拼装流这一行不能抛。抛了就说明异常发生在订阅之前，
        // 后面用 onErrorResume 根本接不到。
        Flux<ChatResponse> exhausted = model.stream(new Prompt("第二次"));

        AtomicReference<Throwable> signal = new AtomicReference<>();
        exhausted.onErrorResume(error -> {
            signal.set(error);
            return Flux.empty();
        }).blockLast();

        assertThat(signal.get()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("脚本已耗尽");
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
