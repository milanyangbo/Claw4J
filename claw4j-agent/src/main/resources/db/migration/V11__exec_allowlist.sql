-- exec 的"以后这个可执行文件都放行"（ADR-0030 裁决四的三态之一：allow-always）。
--
-- 一条记录对**一段**命令，不是对整条命令行：
--     rg -n TODO            → pattern = 'rg'
--     rg -n TODO && ls      → 两条记录：'rg' 与 'ls'
-- 批一条复合命令等于把它的每一段都放开——这正是"按段落"的理由：只落整条命令的哈希，
-- 下一次 `rg -n FIXME` 还要再问一遍，人会开始无脑点批准。
--
-- **没有 command_text 列**。OpenClaw 的记录里有 `commandText`，但那是旧字段、加载时会被
-- stripAllowlistCommandText 剥掉：留着它等于把"用户跑过的一条完整命令"永久写进库，
-- 而匹配一份都不用它。要看人话有 last_used_command，那是一次性的便于排查的记录。
--
-- `last_used_at` / `last_used_command` / `last_resolved_path` 都是**命中之后才补**的：
-- 刚写进去时它们全是 null。不给默认值的理由：默认值会让"从没命中过"与"命中过但没记下来"
-- 看起来一样。
--
-- 唯一键用 coalesce(arg_pattern, '') 而不是 (pattern, arg_pattern)：Postgres 里 NULL 互不相等，
-- 直接建唯一索引时同一个 pattern 能插进无数条 arg_pattern 为 null 的记录，于是每次启动都多一批。

create table claw4j_run.exec_allowlist
(
    id                 uuid        not null primary key,
    pattern            text        not null,
    arg_pattern        text,
    source             text        not null,
    last_used_at       timestamptz,
    last_used_command  text,
    last_resolved_path text,
    created_at         timestamptz not null default now()
);

comment on table claw4j_run.exec_allowlist is
    'exec 的 allow-always 条目：一条对一段命令（ADR-0030）。pattern 是可执行名，arg_pattern 预留参数级收窄';
comment on column claw4j_run.exec_allowlist.pattern is
    '可执行名（如 rg）。路径形态与赋值形态不落库——取 basename 会让 /tmp/evil/rg 与 rg 共用一条';
comment on column claw4j_run.exec_allowlist.arg_pattern is
    '参数级收窄，暂时恒为 null；有它时 pattern + arg_pattern 一起匹配';
comment on column claw4j_run.exec_allowlist.source is
    '谁写的这一条。目前只有 allow-always；留字面量是为了将来区分配置文件导入与人工批准';
comment on column claw4j_run.exec_allowlist.last_resolved_path is
    '上次命中时解析出的可执行文件绝对路径，事后排查用，不参与匹配';

create unique index uq_exec_allowlist_pattern
    on claw4j_run.exec_allowlist (pattern, coalesce(arg_pattern, ''));
