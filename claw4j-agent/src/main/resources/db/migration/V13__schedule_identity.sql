-- 定时任务的身份与"它需要哪些工具"（P05）。
--
-- **为什么把身份快照拆成 user_id + role**：P04 存的是整份 Principal（含 tables / actions），
-- 到点直接拿它去跑。那实际上是一份**角色快照**，而快照不会跟着角色配置变：
-- 一个人的权限昨天被收回，今天那条任务照样按昨天的权限跑完——"收权"这件事
-- 对定时任务等于没有发生。现在只留"这是谁、他当时是什么角色"，
-- 到点那一刻按**当前**的角色表重新算一次权限；算不出来（角色被删了）就不跑。
--
-- **为什么还要记一份工具清单**：光有身份只答得了"他还能不能干活"，
-- 答不了"这条任务要干的那件事还干不干得成"。清单在建的时候核一遍（跑不了当场拒，
-- 不用等到明天九点），到点之前再核一遍（权限被收回时，别开到一半才发现每个工具都被拦）。
-- 清单存的是**工具名**，不是权限：权限一律以到点那一刻的角色表为准。
--
-- 这一批之前建出来的行没有这两样（清单默认为空 = 不额外核），
-- 它们的身份列为空，会被扫描器当成"读不到任务主人"停掉——那是对的，
-- 一条不知道自己在为谁干活的任务不该继续跑。
alter table claw4j_run.agent_schedule
    add column if not exists owner_user_id varchar(255),
    add column if not exists owner_role    varchar(64),
    add column if not exists required_tools text[] not null default '{}';

-- 旧的快照列直接删掉，不留"反正没人读"的余地：留着它，下一个改这段代码的人
-- 会以为里面那份权限还能用，而那正是这次要收掉的形状。
alter table claw4j_run.agent_schedule drop column if exists principal_snapshot;

comment on column claw4j_run.agent_schedule.owner_user_id is
    '任务主人在通道侧的标识（飞书是 open_id，HTTP 是 X-Claw4j-User）。到点按它 + owner_role 重新算权限';
comment on column claw4j_run.agent_schedule.owner_role is
    '创建时的角色名。存角色名而不是存权限：权限要按到点那一刻的角色表现算，否则收权对定时任务无效';
comment on column claw4j_run.agent_schedule.required_tools is
    '这条任务预计要用到的工具名。建的时候与每次到点之前都核一遍；空表示纯模型回答、不碰工具';
