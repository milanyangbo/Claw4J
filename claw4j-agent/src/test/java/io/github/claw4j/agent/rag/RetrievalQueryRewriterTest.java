package io.github.claw4j.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.rag.Query;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索改写的单测。
 *
 * <p>四条要守的边界：含数字不改、正常问题改、模型报错回落、超时回落。前两条决定"改写有没有
 * 误伤精确题"，后两条决定"改写挂了会不会让整轮检索挂掉"——后者如果破了，这个默认关的功能
 * 一旦打开就是单点故障。
 */
class RetrievalQueryRewriterTest {

    @Test
    @DisplayName("含数字或量词的问题不改写：把「万分之五」改成近似值，比不改更糟")
    void numericQuestionsAreLeftAlone() {
        ChatModel model = mock(ChatModel.class);
        RetrievalQueryRewriter rewriter = new RetrievalQueryRewriter(() -> model, Duration.ofSeconds(1));

        for (String question : List.of("违约金按万分之五算吗", "押金 500 元什么时候退", "SKU-2024 还剩多少",
                "7 个工作日是几天")) {
            Query query = query(question);

            assertThat(rewriter.transform(query).text()).as("原样返回：%s", question).isEqualTo(question);
        }
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("正常问题被改写：改写结果就是送去检索的文本")
    void plainQuestionIsRewritten() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class)))
                .thenReturn(response("无书面约定时的退货处理规则"));
        RetrievalQueryRewriter rewriter = new RetrievalQueryRewriter(() -> model, Duration.ofSeconds(1));

        Query rewritten = rewriter.transform(query("没白纸黑字写能退吗"));

        assertThat(rewritten.text()).isEqualTo("无书面约定时的退货处理规则");
    }

    @Test
    @DisplayName("模型输出带标签或引号时清掉：那些字进了检索就成了噪声词")
    void outputIsCleaned() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response("改写：「备机押金的退还流程」\n说明：…"));
        RetrievalQueryRewriter rewriter = new RetrievalQueryRewriter(() -> model, Duration.ofSeconds(1));

        assertThat(rewriter.transform(query("备机押金怎么退")).text()).isEqualTo("备机押金的退还流程");
    }

    @Test
    @DisplayName("模型报错就按原问题检索：改写是增强，不该让这一轮检索失败")
    void failureFallsBackToOriginal() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("502"));
        RetrievalQueryRewriter rewriter = new RetrievalQueryRewriter(() -> model, Duration.ofSeconds(1));

        assertThat(rewriter.transform(query("没白纸黑字写能退吗")).text()).isEqualTo("没白纸黑字写能退吗");
    }

    @Test
    @DisplayName("超过超时就按原问题检索：用户等的是首字，不能陪一次改写等下去")
    void slowModelFallsBackToOriginal() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(2_000);
            return response("慢死了");
        });
        RetrievalQueryRewriter rewriter = new RetrievalQueryRewriter(() -> model, Duration.ofMillis(100));

        long started = System.nanoTime();
        Query result = rewriter.transform(query("没白纸黑字写能退吗"));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(result.text()).isEqualTo("没白纸黑字写能退吗");
        assertThat(elapsedMillis).as("不能在超时值之后还等两秒").isLessThan(1_500);
    }

    @Test
    @DisplayName("空查询不调模型：null 直接放过（空白 Query 在框架里构造不出来）")
    void blankQueryIsNotRewritten() {
        ChatModel model = mock(ChatModel.class);
        RetrievalQueryRewriter rewriter = new RetrievalQueryRewriter(() -> model, Duration.ofSeconds(1));

        assertThat(rewriter.transform(null)).isNull();
        verify(model, never()).call(any(Prompt.class));
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static Query query(String text) {
        return new Query(text, new ArrayList<>(), Map.of());
    }
}
