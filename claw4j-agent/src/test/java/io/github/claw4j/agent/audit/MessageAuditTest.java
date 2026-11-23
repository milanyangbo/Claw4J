package io.github.claw4j.agent.audit;

import io.github.claw4j.domain.audit.MessageDirection;
import io.github.claw4j.domain.audit.MessageOutcome;
import io.github.claw4j.domain.audit.MessageReason;
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
    @DisplayName("入站处理完：一条 message/inbound/completed，带 executionId 与时长")
    void inboundMessageIsAudited() {
        audit.inbound(new MessageAudit.Inbound("run-1", "ops", "feishu", "chat-1", "om-1",
                "u-7", MessageOutcome.COMPLETED, null, 42, true));

        AuditEvent event = capture();
        assertThat(event.action()).isEqualTo(AuditAction.MESSAGE_INBOUND_PROCESSED);
        assertThat(event.status()).isEqualTo(AuditStatus.SUCCEEDED);
        assertThat(event.message().direction()).isEqualTo(MessageDirection.INBOUND);
        assertThat(event.message().outcome()).isEqualTo(MessageOutcome.COMPLETED);
        assertThat(event.message().durationMs()).isEqualTo(42);
        assertThat(event.scope().executionId()).as("这条消息是被哪一轮答的").isEqualTo("run-1");
        assertThat(event.scope().agentId()).isEqualTo("ops");
        assertThat(event.message().conversationKind())
                .as("私聊/群来自通道的判定（ADR-0029），审计只传不猜")
                .isEqualTo("direct");
    }

    @Test
    @DisplayName("入站失败：failed + message_processing_failed，与投递失败分开")
    void failedInboundCarriesItsOwnCode() {
        audit.inbound(new MessageAudit.Inbound(null, null, "slack", "chat-2", "om-2",
                "u-8", MessageOutcome.FAILED, null, 5, null));

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
                "u-9", MessageOutcome.SKIPPED, MessageReason.DUPLICATE, 0, false));

        AuditEvent event = capture();
        assertThat(event.status()).as("跳过不是故障").isEqualTo(AuditStatus.BLOCKED);
        assertThat(event.errorCode()).isNull();
        assertThat(event.message().outcome()).isEqualTo(MessageOutcome.SKIPPED);
        assertThat(event.message().reasonCode()).isEqualTo(MessageReason.DUPLICATE);
        assertThat(event.message().conversationKind()).as("群聊").isEqualTo("group");
    }

    @Test
    @DisplayName("出站送达：sent，带投递形态；失败时带 failureStage")
    void outboundCarriesStageAndKind() {
        audit.outbound(new MessageAudit.Outbound("run-2", "ops", "feishu", "chat-4", "chat-4",
                MessageOutcome.SENT, null, null, "text", 12, null));
        AuditEvent sent = capture();
        assertThat(sent.action()).isEqualTo(AuditAction.MESSAGE_OUTBOUND_FINISHED);
        assertThat(sent.status()).isEqualTo(AuditStatus.SUCCEEDED);
        assertThat(sent.message().direction()).isEqualTo(MessageDirection.OUTBOUND);
        assertThat(sent.message().deliveryKind()).isEqualTo("text");

        audit.outbound(new MessageAudit.Outbound("run-3", "ops", "feishu", "chat-5", "chat-5",
                MessageOutcome.FAILED, null, "platform_send", "text", 30, null));
        AuditEvent failed = capture();
        assertThat(failed.status()).isEqualTo(AuditStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo(AuditErrorCode.MESSAGE_DELIVERY_FAILED);
        assertThat(failed.message().failureStage()).as("失败发生在哪一段").isEqualTo("platform_send");
    }

    @Test
    @DisplayName("身份是假名：不带原文，同一原文算出同一个假名")
    void identityRefsArePseudonymized() {
        audit.inbound(new MessageAudit.Inbound(null, null, "feishu", "chat-6", "om-6",
                "u-mailer", MessageOutcome.COMPLETED, null, 1, null));

        AuditEvent event = capture();
        assertThat(event.message().accountRef())
                .as("账号是假名：拿字典反查不出来")
                .startsWith("hmac-sha256:v1:")
                .doesNotContain("u-mailer");
        assertThat(event.message().conversationRef()).doesNotContain("chat-6");
        assertThat(event.message().messageRef()).doesNotContain("om-6");

        // 同一个原文两次算出同一个假名：否则"是不是同一个人反复没收到"这个问题就断了。
        audit.inbound(new MessageAudit.Inbound(null, null, "feishu", "chat-6", "om-6",
                "u-mailer", MessageOutcome.COMPLETED, null, 1, null));
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
                "u-1", MessageOutcome.COMPLETED, null, 1, null));
        off.outbound(new MessageAudit.Outbound(null, null, "feishu", "chat-7", "chat-7",
                MessageOutcome.SENT, null, null, "text", 1, null));

        verify(ledger, never()).record(org.mockito.ArgumentMatchers.any());
        verify(jdbc, never()).queryForList(anyString(), eq(byte[].class));
    }

    /**
     * 票 12：入站行的事件时刻是"到达那一刻"，比落库时刻早它自己量到的耗时。
     *
     * <p>判据是差值而不是绝对值：写成落库时刻时，这条断言会差 800 毫秒——而那正是"消息处理了
     * 八秒、账本上却显示八秒之后才发生"的那个错误。
     */
    @Test
    @DisplayName("入站行的事件时刻是到达时刻：早 durationMs 那么多（票 12）")
    void inboundRowCarriesArrivalTime() {
        long before = System.currentTimeMillis();
        audit.inbound(new MessageAudit.Inbound("run-1", "ops", "feishu", "chat-1", "om-9",
                "u-7", MessageOutcome.COMPLETED, null, 800, true));

        AuditEvent event = capture();
        long arrival = event.occurredAt().toEpochMilli();
        assertThat(before - arrival)
                .as("到达时刻早于落库时刻约 800 毫秒；写成落库时刻时它会在 [0,500] 里")
                .isBetween(600L, 3000L);
    }

    /**
     * 票 13：被抑制的投递没有交付形态，哪怕生产者给了 {@code text}。
     *
     * <p>据 OpenClaw 出站 {@code suppressed} 变体的 {@code withoutDeliveryKind}
     * （{@code audit-activity.ts:373}）：那批字节没有交付形态。
     */
    @Test
    @DisplayName("被抑制的出站行不带 deliveryKind（票 13）")
    void suppressedRowHasNoDeliveryKind() {
        audit.outbound(new MessageAudit.Outbound("run-4", "ops", "feishu", "chat-8", "chat-8",
                MessageOutcome.SUPPRESSED, MessageReason.NO_VISIBLE_PAYLOAD, null, "text", 3, null));

        AuditEvent event = capture();
        assertThat(event.status()).isEqualTo(AuditStatus.BLOCKED);
        assertThat(event.message().deliveryKind())
                .as("被抑制的投递被当成一次文本发送，是这一票要修的那种脏数据")
                .isNull();
    }

    /**
     * 票 13：失败与"不知道"必须说清卡在哪一段；通道没给时写 {@code unknown}，不留空。
     *
     * <p>据 {@code audit-activity.ts:380,388}：{@code failed} 与 {@code unknown} 两个变体都要求
     * {@code failureStage}。留空的话读的人分不清"没记"与"不知道卡在哪"。
     */
    @Test
    @DisplayName("失败的出站行带 failureStage，缺了写 unknown（票 13）")
    void failedRowSaysWhereItStopped() {
        audit.outbound(new MessageAudit.Outbound("run-5", "ops", "feishu", "chat-9", "chat-9",
                MessageOutcome.FAILED, null, null, null, 30, null));

        AuditEvent event = capture();
        assertThat(event.message().failureStage())
                .as("没有阶段信息不是「没记」，是「不知道卡在哪」")
                .isEqualTo("unknown");
    }

    /** 最后记下来的那一条。用 atLeastOnce 是因为同一用例里可能记两条（成功一次、失败一次）。 */
    private AuditEvent capture() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(ledger, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
        return captor.getValue();
    }
}
