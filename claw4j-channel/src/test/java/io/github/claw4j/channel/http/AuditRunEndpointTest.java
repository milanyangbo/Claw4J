package io.github.claw4j.channel.http;

import io.github.claw4j.domain.port.AuditQuery;
import io.github.claw4j.domain.port.AuditRunInspect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一次 run 的全景端点（ADR-0037 票 14）：照 {@code audit.run.inspect}。
 *
 * <p>这一层只判两件事：**报文是它那份闭对象**（必填格都在、空字段不出现、数组恒在），以及
 * **三个参数各有各的拒绝方式**（不支持的执行编号、认不出来的游标、没装配时 404）。
 * 合并与排序在 {@code JdbcAuditRunInspect} 里，由真库那条用例验。
 */
class AuditRunEndpointTest {

    private static final Instant AT = Instant.parse("2026-11-23T10:00:00Z");

    @Test
    @DisplayName("全景：信封必填格都在，identity 照实报 unsupported，数组恒在")
    void anInspectionCarriesTheClosedShape() {
        AuditRunInspect fake = (runId, limit, cursor) -> Optional.of(new AuditRunInspect.Report(true,
                List.of(
                        new AuditRunInspect.Decision("approval-decision:a-1", AT, "approval", "Bash",
                                "allowed", "allowed", "enforced", "operator-approval"),
                        new AuditRunInspect.Decision("message-decision:7", AT.minusSeconds(1), "message",
                                "delivery", "allowed", "sent", "attribution-only", "message-delivery")),
                null));

        ResponseEntity<?> response = controller(fake).inspect("run-1", null, 50, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        AuditRunView view = (AuditRunView) response.getBody();
        assertNotNull(view);
        String json = JsonMapper.builder().build().writeValueAsString(view);

        assertTrue(json.contains("\"schemaVersion\":1"), json);
        assertTrue(json.contains("\"status\":\"known\""), "这一轮在库里找得到：" + json);
        assertTrue(json.contains("\"state\":\"unsupported\""),
                "我们没有采集执行身份，就照实报 unsupported——写 present 等于声称知道「谁发起的」：" + json);
        assertTrue(json.contains("\"reasonCode\":\"execution_identity_not_collected\""), json);
        assertTrue(json.contains("\"selectorId\":\"approval-decision:a-1\""), json);
        assertTrue(json.contains("\"occurredAt\":" + AT.toEpochMilli()),
                "时刻是 Unix 毫秒整数，不是 ISO 字符串：" + json);
        assertTrue(json.contains("\"family\":\"approval\"") && json.contains("\"family\":\"message\""),
                json);
        assertTrue(json.contains("\"state\":\"verified\",\"producer\":\"operator-approval\""),
                "审批是它闭集里的生产者之一：" + json);
        assertTrue(json.contains("\"coverage\":{\"state\":\"enforced\",\"missingEvidence\":[]}"),
                "有一条真闸就是 enforced，空数组也要空成 []：" + json);
        assertTrue(json.contains("\"missingEvidence\":[]") && json.contains("\"remediation\":[]"),
                "三个数组在它的 schema 里必填，空也要出现：" + json);
        assertTrue(!json.contains(":null"), "空字段整格省掉（闭对象）：" + json);
    }

    @Test
    @DisplayName("全景：游标认不出来 400，没装配时 404，executionId 不支持也 400")
    void refusedArgumentsAreExplained() {
        AuditRunInspect unknownCursor = (runId, limit, cursor) -> Optional.empty();
        ResponseEntity<?> badCursor = controller(unknownCursor)
                .inspect("run-1", null, 50, "not-a-selector");
        assertEquals(HttpStatus.BAD_REQUEST, badCursor.getStatusCode());
        assertTrue(((AuditController.Problem) badCursor.getBody()).error().contains("decisionCursor"));

        assertEquals(HttpStatus.NOT_FOUND,
                controller(null).inspect("run-1", null, 50, null).getStatusCode());

        AuditRunInspect neverCalled = (runId, limit, cursor) -> {
            throw new AssertionError("不支持的执行编号不该去查库");
        };
        ResponseEntity<?> execution = controller(neverCalled)
                .inspect("run-1", "exec-9", 50, null);
        assertEquals(HttpStatus.BAD_REQUEST, execution.getStatusCode());
        assertTrue(((AuditController.Problem) execution.getBody()).error().contains("executionId"));
    }

    /** 这一轮不存在时仍然回 200，只是 `run.status` 是 unknown、回执为空。 */
    @Test
    @DisplayName("全景：找不到这一轮时 status=unknown，不是 404")
    void anUnknownRunIsStillAnswered() {
        AuditRunInspect missing = (runId, limit, cursor) ->
                Optional.of(new AuditRunInspect.Report(false, List.of(), null));

        ResponseEntity<?> response = controller(missing).inspect("run-gone", null, 50, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String json = JsonMapper.builder().build()
                .writeValueAsString((AuditRunView) response.getBody());
        assertTrue(json.contains("\"status\":\"unknown\""), json);
        assertTrue(json.contains("\"coverage\":{\"state\":\"unknown\""),
                "没有回执时覆盖度是 unknown，不是 enforced：" + json);
    }

    private static AuditController controller(AuditRunInspect inspect) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (inspect != null) {
            beans.registerSingleton("auditRunInspect", inspect);
        }
        // 这一组用例不装配列表查询：它验的是全景端点，列表端点有自己的用例。
        return new AuditController(beans.getBeanProvider(AuditQuery.class),
                beans.getBeanProvider(AuditRunInspect.class));
    }
}
