package io.github.claw4j.agent.audit;

import io.github.claw4j.domain.audit.MessageDirection;
import io.github.claw4j.domain.audit.MessageOutcome;
import io.github.claw4j.domain.port.MessageAudit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 消息审计落到同一本账上（ADR-0037 票 04）。
 *
 * <p>落 {@code claw4j_run.audit_event}，与运行、工具事件同一张表、同一个保留期、同一个读接口：
 * 「客户说没收到」这条问题要从一条入站行连着查到一轮回答，分两张表就得在应用层拼，而拼错不报错。
 *
 * <p>{@code status} 由 {@code outcome} 推出来（成功 / 被跳过 / 失败 / 不知道），错误码只在
 * 真的失败时带——这个映射放在这一处，是因为它必须与 {@code V12} 的配对约束一致，两处各写一份的话，
 * 不一致的表现是「审计写不进去」，而那会安静地吞掉整条消息。
 *
 * <p>开关 {@code claw4j.audit.messages} 默认关：消息审计比其他两类多出"谁跟谁说了话"这种关系数据，
 * 而 OpenClaw 也把它默认关掉（{@code docs/gateway/protocol/ledgers.md:117-118}）。关掉时不写新行，
 * 但已有行照常可读、照常按保留期清。
 */
public class JdbcMessageAudit implements MessageAudit {

    private static final Logger log = LoggerFactory.getLogger(JdbcMessageAudit.class);

    /** 出站没有可见内容时的原因码：这个仓库还没有 hook 那条链，被策略挡下也记它。 */
    private static final String NO_VISIBLE_PAYLOAD = "no_visible_payload";

    private static final String ACTOR_CHANNEL_SENDER = "channel_sender";

    private static final String ACTOR_AGENT = "agent";

    /** 这个仓库目前只有一对一与群两种可分辨的会话；分不出来的写 unknown（闭集里本来就有）。 */
    private static final String CONVERSATION_UNKNOWN = "unknown";

    private static final String DELIVERY_TEXT = "text";

    private final AuditLedger ledger;

    private final AuditPseudonyms pseudonyms;

    private final boolean enabled;

    /**
     * @param ledger 账本，必填
     * @param pseudonyms 假名，必填
     * @param enabled 是否写消息行（配置 {@code claw4j.audit.messages}）
     */
    public JdbcMessageAudit(AuditLedger ledger, AuditPseudonyms pseudonyms, boolean enabled) {
        this.ledger = ledger;
        this.pseudonyms = pseudonyms;
        this.enabled = enabled;
    }

    /**
     * 一条入站消息处理完了。
     *
     * @param record 这一条的事实
     */
    @Override
    public void inbound(Inbound record) {
        if (!enabled || record == null) {
            return;
        }
        AuditEvent.Message message = new AuditEvent.Message(
                MessageDirection.INBOUND, record.outcome(), record.reasonCode(),
                CONVERSATION_UNKNOWN, null, null,
                (int) Math.max(0, record.durationMs()), 1, ACTOR_CHANNEL_SENDER,
                pseudonyms.ref(record.actorId()), pseudonyms.ref(record.chatRef()),
                pseudonyms.ref(record.messageRef()), null);
        write(AuditEvent.inboundMessage(scope(record.runId(), record.agentId(), record.channel()),
                statusOf(record.outcome()), errorOf(record.outcome(), true),
                message, inboundSource(record)));
    }

    /**
     * 一条出站消息投递结束。
     *
     * @param record 这一条的事实
     */
    @Override
    public void outbound(Outbound record) {
        if (!enabled || record == null) {
            return;
        }
        AuditEvent.Message message = new AuditEvent.Message(
                MessageDirection.OUTBOUND, record.outcome(), record.reasonCode(),
                CONVERSATION_UNKNOWN,
                record.deliveryKind() == null ? DELIVERY_TEXT : record.deliveryKind(),
                record.failureStage(),
                (int) Math.max(0, record.durationMs()), null, ACTOR_AGENT,
                null, pseudonyms.ref(record.chatRef()), null, pseudonyms.ref(record.targetRef()));
        write(AuditEvent.outboundMessage(scope(record.runId(), record.agentId(), record.channel()),
                statusOf(record.outcome()), errorOf(record.outcome(), false),
                message, outboundSource(record)));
    }

    /**
     * 入站行的来源键。
     *
     * <p>平台重投同一条消息时这个键相同，唯一键于是把第二次投递挡成重复——「平台重推三次、
     * 我们只记一次」就是靠它。没有消息编号时只能退回一个一次性的键：那时去重不可能，
     * 因为"这两条是不是同一条"本来就无从判断。
     */
    private static String inboundSource(Inbound record) {
        String ref = record.messageRef();
        return ref == null || ref.isBlank()
                ? "message:in:" + java.util.UUID.randomUUID()
                : "message:in:" + ref;
    }

    private static String outboundSource(Outbound record) {
        String runId = record.runId();
        String chatRef = record.chatRef() == null ? "" : record.chatRef();
        // 一轮对一段对话只投递一次，所以 (runId, 地址) 就是这条投递的身份；
        // 没有 runId（定时任务、审批续跑）时只能一次性的——退回时间戳会让重放多出一行。
        return runId == null || runId.isBlank()
                ? "message:out:" + java.util.UUID.randomUUID()
                : "message:out:" + runId + ':' + chatRef;
    }

    private static AuditEvent.Scope scope(String runId, String agentId, String channel) {
        return AuditEvent.Scope.of(runId, null, agentId, null, channel);
    }

    /**
     * 结果到通用状态的映射。
     *
     * <p>被跳过是 {@code blocked} 而不是 {@code failed}：它不是故障，是"这条不该处理"。
     * 这个区分决定读的人去查"重复投递"还是查"系统挂了"。
     */
    private static AuditStatus statusOf(MessageOutcome outcome) {
        if (outcome == null) {
            return AuditStatus.UNKNOWN;
        }
        return switch (outcome) {
            case COMPLETED, SENT -> AuditStatus.SUCCEEDED;
            case SKIPPED, SUPPRESSED -> AuditStatus.BLOCKED;
            case FAILED -> AuditStatus.FAILED;
            case UNKNOWN -> AuditStatus.UNKNOWN;
        };
    }

    private static AuditErrorCode errorOf(MessageOutcome outcome, boolean inbound) {
        if (outcome != MessageOutcome.FAILED) {
            return null;
        }
        return inbound ? AuditErrorCode.MESSAGE_PROCESSING_FAILED : AuditErrorCode.MESSAGE_DELIVERY_FAILED;
    }

    private void write(AuditEvent event) {
        try {
            ledger.record(event);
        } catch (RuntimeException failure) {
            // 账本自己已经吃掉存储异常；走到这里的是更意外的东西，仍然不该让一条消息失败。
            log.debug("写消息审计时出错：{}", event.action().column(), failure);
        }
    }
}
