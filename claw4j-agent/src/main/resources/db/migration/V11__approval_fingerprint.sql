-- 审批从"这个工具"细化到"这个工具的这一种用法"（P02）。
--
-- **为什么必须细化**：P01 的账本只按（人 + 工具名）问，于是"批了查一次上周成交额"
-- 同时放行了"查全表"。加进来的是参数指纹 —— sha256(工具名 + 规范化参数)，
-- 对象 key 递归排序、数字归一（见 CallFingerprint）。参数变了就是另一次，要重新批。
--
-- **指纹不含 runId、不含序号**：这与幂等键（ADR-0017）刚好相反，且是最容易被"顺手改对"
-- 的一处。幂等键要区分每一次执行（否则重复发一封邮件），审批指纹要跨执行复用一次批准
-- （否则每件新事都要重批，人会开始闭眼点）。两个哈希形状相似、口径相反：
-- 把 runId 混进来，跨任务复用批准这件事就悄悄没了；把审批指纹当幂等键用，
-- 同一件事里第二次同参数调用会被当成"已经做过"跳过。
alter table claw4j_run.approval_request
    -- 空串是"P01 时期按工具名批的那条，没有指纹可比"。它永远不会被精确匹配到
    -- （真指纹是 64 位十六进制），也就是升级之后要重批一次——这是对的：
    -- 一条不知道批的是什么的批准，不该在口径收紧之后继续生效。
    add column if not exists args_digest  varchar(64)  not null default '',
    -- 给人看的参数摘要（已脱敏、已截断）。审批人看不到自己批的是什么时，点下去等于盖章。
    -- ADR-0016 说"step 不存参数原文"，这里存的是**摘要**不是原文，理由同上：
    -- 不存摘要的话，审批这个机制就退化成一个盲签的按钮。
    add column if not exists args_summary varchar(512),
    -- 一次性：兑现过的批准不再有效。列一个 consumed_at 而不是删行，
    -- 因为"这条批准被谁用过、什么时候用掉的"正是事后要追的东西（P07）。
    add column if not exists consumed_at  timestamptz;

-- 判定只按（人 + 工具 + 指纹）查当前有效的那条，索引跟着这个形状走；
-- 部分索引带上 consumed_at is null：已兑现的行不会再被读到，不该占索引。
drop index if exists claw4j_run.ix_approval_request_lookup;

create index if not exists ix_approval_request_lookup
    on claw4j_run.approval_request (owner, tool_name, args_digest, status)
    where consumed_at is null;

comment on column claw4j_run.approval_request.args_digest is
    '参数指纹 sha256(工具名 + 规范化参数)。不含 runId 与序号：批准要跨执行复用（与幂等键口径相反）';
comment on column claw4j_run.approval_request.args_summary is
    '给人看的参数摘要（脱敏 + 截断）。不存原文，但也不该让人盲签';
comment on column claw4j_run.approval_request.consumed_at is
    '被兑现的时间。一次性：兑现过的批准不再有效，但这一行留着，审计要问"谁批的、谁用掉的"';

-- step 多一个"等审批"（P02）。
--
-- **只加在非 plan 的那一支**：计划里的"待做"是 PENDING，与"一个动作在等人点头"
-- 是两件事——前者是打算，后者已经发起了。一把放开会让"第 3 步还没做"和
-- "第 3 步卡着等审批"变成同一个值，而它们的下一步动作完全不同。
--
-- **不回头改 V7**：它已经在跑过的环境里应用过了，改内容会让 Flyway 报校验和不符。
-- 迁移是不可变的历史，加约束走新版本。
alter table claw4j_run.agent_step drop constraint if exists ck_agent_step_status;

alter table claw4j_run.agent_step
    add constraint ck_agent_step_status check (
        case kind
            when 'plan' then status in ('pending', 'done', 'skipped')
            else status in ('done', 'failed', 'awaiting')
            end);

-- run 的发起人快照（P02）。
--
-- **为什么服务端续跑需要它**：批准之后要接着调模型，而工具调用的鉴权要一个 Principal。
-- 用审批人的身份续跑等于提权（审批人权限通常比提问人大）；只存 principal_digest
-- 又反推不出角色与可访问表。所以进门时把发起人的身份整份存下来，续跑时用它——
-- "接着做这件事的是当初要干这件事的那个人"，不是"批它的那个人"。
--
-- 存 JSON 而不是拆成三列：它是快照，不是一个要 join 的实体；拆成列之后
-- 每加一个身份维度就要加一列迁移，而它作为快照本来就不该参与查询。
alter table claw4j_run.agent_run
    add column if not exists principal_snapshot text;

comment on column claw4j_run.agent_run.principal_snapshot is
    '发起人的身份快照（JSON）。服务端续跑用它鉴权：接着做的是当初那个人，不是批它的那个人';
