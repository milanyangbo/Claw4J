-- 票 13（ADR-0037 第四批）：消息行的必填与互斥，照它的四个变体收紧
--
-- 依据 packages/gateway-protocol/src/schema/audit-activity.ts：
--   :122-133 消息变体公共部分  channel / conversationKind **必填**
--   :355-395 出站四个终态      sent 不带 reason/failureStage；suppressed 必带 reason 且**不带 deliveryKind**；
--                             failed / unknown 必带 failureStage，unknown 还**不带 deliveryKind**
--
-- 这些格子现在靠生产者自觉。自觉不会报错，只会产出"读侧必填格空着"或"被抑制的投递也写着文本"的行，
-- 而那种行在严格客户端那里解析不了。所以先把历史行按同一规则归一，再用 CHECK 钉住。

-- ① 消息行必须有通道与对话类别。缺的那批是票 04 之前写的（那时还没有这两列的必填约定），
--    补 unknown：与它闭集里的取值同一个词，比删行好——删掉就再也查不到"这条消息存在过"。
update claw4j_run.audit_event set channel = 'unknown'
 where kind = 'message' and channel is null;

update claw4j_run.audit_event set conversation_kind = 'unknown'
 where kind = 'message' and conversation_kind is null;

-- ② 被抑制 / 结果不明：没有交付，就没有交付形态。
update claw4j_run.audit_event set delivery_kind = null
 where kind = 'message' and message_outcome in ('suppressed', 'unknown')
   and delivery_kind is not null;

-- ③ 失败 / 结果不明：必须说清卡在哪一段。不知道时写 unknown——那也是它闭集里的一个值，
--    比留空诚实：空着读的人分不清"没记"与"不知道"。
update claw4j_run.audit_event set failure_stage = 'unknown'
 where kind = 'message' and message_outcome in ('failed', 'unknown')
   and failure_stage is null;

-- ④ 出站的原因码与结果一一对应：被抑制必有原因，送出去了/失败了/不知道都不该带原因。
update claw4j_run.audit_event set reason_code = 'no_visible_payload'
 where kind = 'message' and direction = 'outbound' and message_outcome = 'suppressed'
   and reason_code is null;

update claw4j_run.audit_event set reason_code = null
 where kind = 'message' and direction = 'outbound' and message_outcome in ('sent', 'failed', 'unknown')
   and reason_code is not null;

-- ⑤ 四条约束
alter table claw4j_run.audit_event
    add constraint ck_audit_event_message_required check (
        kind <> 'message' or (channel is not null and conversation_kind is not null));

alter table claw4j_run.audit_event
    add constraint ck_audit_event_delivery_kind_absent check (
        kind <> 'message' or message_outcome not in ('suppressed', 'unknown')
        or delivery_kind is null);

alter table claw4j_run.audit_event
    add constraint ck_audit_event_failure_stage_required check (
        kind <> 'message' or message_outcome not in ('failed', 'unknown')
        or failure_stage is not null);

alter table claw4j_run.audit_event
    add constraint ck_audit_event_outbound_reason check (
        kind <> 'message' or direction <> 'outbound'
        or (message_outcome = 'suppressed' and reason_code is not null)
        or (message_outcome <> 'suppressed' and reason_code is null));

comment on constraint ck_audit_event_message_required on claw4j_run.audit_event is
    '消息行必须有 channel 与 conversation_kind：读侧四个变体里它们是必填（audit-activity.ts:122-133）';
comment on constraint ck_audit_event_delivery_kind_absent on claw4j_run.audit_event is
    'suppressed / unknown 不带 deliveryKind：那批字节没有交付形态（:373、:392 的 withoutDeliveryKind）';
comment on constraint ck_audit_event_failure_stage_required on claw4j_run.audit_event is
    'failed / unknown 必带 failureStage：读的人要分清卡在队列还是平台（:380、:388）';
comment on constraint ck_audit_event_outbound_reason on claw4j_run.audit_event is
    '出站被抑制必有原因码，其余终态不带原因码（:360-395）';
