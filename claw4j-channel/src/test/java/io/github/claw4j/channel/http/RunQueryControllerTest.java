package io.github.claw4j.channel.http;

import io.github.claw4j.channel.spi.PrincipalRejectedException;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.RunQuery;
import io.github.claw4j.domain.port.RunView;
import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code GET /api/runs/{id}} 的对外形状（A05）。
 *
 * <p><b>为什么单独一份</b>：这条路的错法与聊天那条完全不同。聊天答错是"内容不对"，
 * 这里答错是<b>把不属于自己的那件事的形状透出去</b>——它不报错、日志干净、
 * 只在某天有人拿着一个 id 试出来时才看得出来。所以它要断的是
 * "查不到与不是你的给出同一个答案"，而不是"结果对不对"（那是 agent 侧的账）。
 */
class RunQueryControllerTest {

    private static final String RUN_ID = "run-7";

    @Test
    @DisplayName("本人查自己的事：200，带上状态、全文与过程")
    void ownerGetsTheRun() {
        ResponseEntity<RunView> response = controller(new FixedQuery(Optional.of(view()))).run(RUN_ID, "u-1", "read-only");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        RunView body = response.getBody();
        assertNotNull(body);
        assertEquals("华东上周成交 128400", body.result(), "查结果的人要的是全文，不是截断摘要");
        assertEquals("done", body.status());
        assertEquals(NotifyPolicy.DONE_ONLY, body.notifyPolicy(), "没收到推送时，人得能看出这是正常的还是丢了");
        assertEquals(1, body.steps().size());
    }

    @Test
    @DisplayName("没有这个 run、或者不是你的：同一个 404，不替对方确认 id 存在")
    void strangerAndMissingLookTheSame() {
        ResponseEntity<RunView> response = controller(new FixedQuery(Optional.empty()))
                .run(RUN_ID, "u-9", "read-only");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    @DisplayName("没装配 run 存储：一律 404，控制器照样在（端点凭空消失更难查）")
    void withoutARunStoreEverythingIs404() {
        ResponseEntity<RunView> response = controller(null).run(RUN_ID, "u-1", "read-only");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("认人失败回 401：与聊天那一侧同一个口径，不放匿名请求进来")
    void principalRejectionMapsTo401() {
        ResponseEntity<RunQueryController.ErrorResponse> response = controller(new FixedQuery(Optional.of(view())))
                .onPrincipalRejected(new PrincipalRejectedException("缺少 X-Claw4j-User 请求头"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("缺少 X-Claw4j-User 请求头", response.getBody().error());
    }

    // ---------------------------------------------------------------- 装配

    /** @param query 为 null 表示容器里没有 run 存储——那个 bean 缺席时端点仍要在。 */
    private static RunQueryController controller(RunQuery query) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (query != null) {
            beans.registerSingleton("runQuery", query);
        }
        return new RunQueryController(beans.getBeanProvider(RunQuery.class),
                new HttpPrincipalResolver(new HttpChannelProperties()));
    }

    private static RunView view() {
        return new RunView(RUN_ID, "上周华东成交额", "done", "华东上周成交 128400",
                NotifyPolicy.DONE_ONLY,
                List.of(new RunView.Step(1, "model_turn", "done", "华东上周成交 128400")));
    }

    /** 一个只认人、不查库的回收口：归属判定由 agent 侧那份实现负责。 */
    private static final class FixedQuery implements RunQuery {

        private final Optional<RunView> answer;

        FixedQuery(Optional<RunView> answer) {
            this.answer = answer;
        }

        @Override
        public Optional<RunView> find(String runId, Principal reader) {
            return answer;
        }
    }
}
