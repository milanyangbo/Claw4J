-- 角色这一层下线（ADR-0030 裁决三、票 04）。
--
-- agent_schedule 原来记两列：owner_user_id 与 owner_role。到点那一刻拿这两个值去角色表里
-- **重新算**一次权限，算不出来就不跑——这条设计的目的正是"不存权限快照"。现在角色表本身
-- 没了（Principal 只有 userId），于是 owner_role 没有读者，也没有写者。
--
-- 为什么 drop 而不是留着不管：留着它，schema 上会有一个永远为空的列，而下一个读这张表的人
-- 会以为"任务是有角色的，只是这一批没填"——那正是最难查的一类偏差（文档与表结构比代码走得远）。
--
-- 为什么不是"清空这一列就行"：清空之后列还在，问题与上面一样。
--
-- 历史行不用迁移：owner_user_id 本来就是唯一的身份事实，删掉 owner_role 不改变任何一条任务
-- 的行为。到点仍然按 owner_user_id 起跑，读不出主人就停用并说明原因（ScheduleScanner）。
alter table claw4j_run.agent_schedule
    drop column if exists owner_role;

comment on column claw4j_run.agent_schedule.owner_user_id is
    '任务主人在通道侧的标识（飞书是 open_id，HTTP 是 X-Claw4j-User）。到点只用它起跑——角色那一层在 ADR-0030 里已下线';
