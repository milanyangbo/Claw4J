-- 计划 step 的三态（A03）。V6 那句 check 只认 done / failed，而"待做"恰恰是计划
-- 要记的东西：计划写下来的那一刻，一步都还没做。
--
-- **不回头改 V6**：它已经在跑过的环境里应用过了，改内容会让 Flyway 报校验和不符，
-- 而修它的办法是 clean（那是要删库的）。迁移是不可变的历史，加约束走新版本。
--
-- 约束按 kind 分支，而不是把四个值一律放开：只有计划才谈得上"待做"与"跳过"，
-- 已经发生的一步（模型轮、工具调用）仍然只有成了还是没成。
-- 一把不区分的约束会让"第 3 步没做成"和"第 3 步还没做"变成同一句话，
-- 而这两句的下一步动作完全不同（重试 vs 接着往下做）。
alter table claw4j_run.agent_step drop constraint if exists ck_agent_step_status;

alter table claw4j_run.agent_step
    add constraint ck_agent_step_status check (
        case kind
            when 'plan' then status in ('pending', 'done', 'skipped')
            else status in ('done', 'failed')
            end);

-- V6 留了这一列没用，A03 给它一个意思：**当前这版计划的起始 seq**。
-- 计划被重写时旧的那版不删（审计要能回答"当时打算怎么干"），
-- 新的一版从下一个 seq 接着排；读计划只取 seq >= 这个值的那些行，
-- 于是"第 2 步"指的永远是清单里的第二个，而不是库里 seq = 2 的那一行。
comment on column claw4j_run.agent_run.plan_ref is
    '当前这版计划的起始 seq（agent_step.seq）；没有计划时为 null';
