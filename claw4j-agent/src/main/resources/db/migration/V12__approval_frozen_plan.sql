-- 审批时冻结一份"要跑什么"，执行时用冻结的那份（ADR-0030 裁决四·性质 5、阶段 5）。
--
-- 为什么要有这一列：批准的对象是**那一条具体命令**，不是"这个工具"。只存参数指纹（args_digest）
-- 时，改动之后命不中，表现出来的只是一句"没有批准"——人能做的只有再批一次，而看不出差别在哪。
-- 存下批准那一刻的原文，才答得出"批的是 `./run.sh a`，现在要跑的是 `./run.sh b`"，那句
-- approval mismatch 才是可排查的。
--
-- **一份 JSON 而不是拆成列**：它是一份快照，不参与查询、不 join、不需要索引。拆成
-- command / cwd 两列之后，每次计划里多一样（脚本类还要加被调文件的哈希）就要一次迁移，
-- 而这一列的读法只有"按 id 取出来整份比"。ADR 裁决四明确写了"JSON 或分列，实现定"。
--
-- 可以为空：除 exec 之外的审批（工具名 + 参数指纹就够定位"批的是什么"）不写它；V12 之前
-- 落下的行也没有。为空时按"这条批准说不清批的是哪条命令"处理——不放行，要重批一次。
--
-- 顺带改掉 decision 那一列的注释：V6 写着"当前只写 allow-once 与 deny"，因为当时没有兑现
-- allow-always 的表。现在有了（exec_allowlist），选"以后都放行"时这一列写 allow-always，
-- 同时按命令段往 exec_allowlist 里逐段落条目。

alter table claw4j_run.approval_request
    add column frozen_plan jsonb;

comment on column claw4j_run.approval_request.frozen_plan is
    '批准那一刻冻结的执行计划（exec：command + cwd）。执行前与它逐字符比，不一致按 approval mismatch 拒；为空表示这条批准说不清批的是哪条命令';

comment on column claw4j_run.approval_request.decision is
    '人给的是什么结论：allow-once 一次性 / allow-always 长期（同时落 exec_allowlist）/ deny 不许';
