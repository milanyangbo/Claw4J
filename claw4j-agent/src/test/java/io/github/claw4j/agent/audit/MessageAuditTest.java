package io.github.claw4j.agent.audit;

import io.github.claw4j.domain.audit.MessageDirection;
import io.github.claw4j.domain.audit.MessageOutcome;
import io.github.claw4j.domain.port.MessageAudit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消息审计这一层（ADR-0037 票 04）：收发各一条终态行，身份是假名，开关默认关。
 *
 * <p>判据不看 SQL：这一层要回答的是"记了哪一条、带了什么"。库那一侧（列、check、假名密钥表）
 * 由 {@code BuildContractTest} 在真 Postgres 上验，两者差一层，不重复。
 */
class MessageAuditTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.UTF_8);

    private AuditLedger ledger;

    private JdbcMessageAudit audit;

    @BeforeEach
    void setUp() {
        ledger = mock(AuditLedger.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(byte[].class))).thenReturn(List.of(SECRET));
        audit = new JdbcMessageAudit(ledger, new AuditPseudonyms(jdbc), true);
    }

    @Test
    @DisplayName("入站处理完：一条 message/inbound/completed，带 runId 与时长")
    void inboundMessageIsAudited() {
        audit.inbound(new MessageAudit.Inbound("run-1", "ops", "feishu", "chat-1", "om-1",
                "u-7", MessageOutcome.COMPLETED, null, 42));

        AuditEvent event = capture();
        assertThat(event.action()).isEqualTo(AuditAction.MESSAGE_INBOUND_PROCESSED);
        assertThat(event.status()).isEqualTo(AuditStatus.SUCCEEDED);
        assertThat(event.message().direction()).isEqualTo(MessageDirection.INBOUND);
        assertThat(event.message().outcome()).isEqualTo(MessageOutcome.COMPLETED);
        assertThat(event.message().durationMs()).isEqualTo(42);
        assertThat(event.scope().runId()).as("这条消息是被哪一轮答的").isEqualTo("run-1");
        assertThat(event.scope().agentId()).isEqualTo("ops");
    }

    @Test
    @DisplayName("入站失败：failed + message_processing_failed，与投递失败分开")
    void failedInboundCarriesItsOwnCode() {
        audit.inbound(new MessageAudit.Inbound(null, null, "slack", "chat-2", "om-2",
                "u-8", MessageOutcome.FAILED, null, 5));

        AuditEvent event = capture();
        assertThat(event.status()).isEqualTo(AuditStatus.FAILED);
        assertThat(event.errorCode())
                .as("没处理成与没送出去是两件事：处置也不同")
                .isEqualTo(AuditErrorCode.MESSAGE_PROCESSING_FAILED);
    }

    @Test
    @DisplayName("重复投递被跳过：blocked/skipped/duplicate，不是 failed")
    void duplicateInboundIsSkipped() {
        audit.inbound(new MessageAudit.Inbound(null, null, "feishu", "chat-3", "om-3",
                "u-9", MessageOutcome.SKIPPED, MessageReason.DUPLICATE, 0));

        AuditEvent event = capture();
        assertThat(event.status()).as("跳过不是故障").isEqualTo(AuditStatus.BLOCKED);
        assertThat(event.errorCode()).isNull();
        assertThat(event.message().outcome()).isEqualTo(MessageOutcome.SKIPPED);
        assertThat(event.message().reasonCode()).isEqualTo(MessageReason.DUPLICATE);
    }

    @Test
    @DisplayName("出站送达：sent，带投递形态；失败时带 failureStage")
    void outboundCarriesStageAndKind() {
        audit.outbound(new MessageAudit.Outbound("run-2", "ops", "feishu", "chat-4", "chat-4",
                MessageOutcome.SENT, null, null, "text", 12));
        AuditEvent sent = capture();
        assertThat(sent.action()).isEqualTo(AuditAction.MESSAGE_OUTBOUND_FINISHED);
        assertThat(sent.status()).isEqualTo(AuditStatus.SUCCEEDED);
        assertThat(sent.message().direction()).isEqualTo(MessageDirection.OUTBOUND);
        assertThat(sent.message().deliveryKind()).isEqualTo("text");

        audit.outbound(new MessageAudit.Outbound("run-3", "ops", "feishu", "chat-5", "chat-5",
                MessageOutcome.FAILED, null, "platform_send", "text", 30));
        AuditEvent failed = capture();
        assertThat(failed.status()).isEqualTo(AuditStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo(AuditErrorCode.MESSAGE_DELIVERY_FAILED);
        assertThat(failed.message().failureStage()).as("失败发生在哪一段").isEqualTo("platform_send");
    }

    @Test
    @DisplayName("身份是假名：不带原文，同一原文算出同一个假名")
    void identityRefsArePseudonymized() {
        audit.inbound(new MessageAudit.Inbound(null, null, "feishu", "chat-6", "om-6",
                "u-mailer", MessageOutcome.COMPLETED, null, 1));

        AuditEvent event = capture();
        assertThat(event.message().accountRef())
                .as("账号是假名：拿字典反查不出来")
                .startsWith("hmac-sha256:v1:")
                .doesNotContain("u-mailer");
        assertThat(event.message().conversationRef()).doesNotContain("chat-6");
        assertThat(event.message().messageRef()).doesNotContain("om-6");

        // 同一个原文两次算出同一个假名：否则"是不是同一个人反复没收到"这个问题就断了。
        audit.inbound(new MessageAudit.Inbound(null, null, "feishu", "chat-6", "om-6",
                "u-mailer", MessageOutcome.COMPLETED, null, 1));
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(ledger, org.mockito.Mockito.times(2)).record(captor.capture());
        assertThat(captor.getAllValues().get(1).message().accountRef())
                .isEqualTo(captor.getAllValues().get(0).message().accountRef());
    }

    @Test
    @DisplayName("开关关掉：一行都不写，也不去取密钥")
    void messagesSwitchOffSkipsWrites() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcMessageAudit off = new JdbcMessageAudit(ledger, new AuditPseudonyms(jdbc), false);

        off.inbound(new MessageAudit.Inbound(null, null, "feishu", "chat-7", "om-7",
                "u-1", MessageOutcome.COMPLETED, null, 1));
        off.outbound(new MessageAudit.Outbound(null, null, "feishu", "chat-7", "chat-7",
                MessageOutcome.SENT, null, null, "text", 1));

        verify(ledger, never()).record(org.mockito.ArgumentMatchers.any());
        verify(jdbc, never()).queryForList(anyString(), eq(byte[].class));
    }

    /** 最后记下来的那一条。用 atLeastOnce 是因为同一用例里可能记两条（成功一次、失败一次）。 */
    private AuditEvent capture() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(ledger, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
        return captor.getValue();
    }
}
