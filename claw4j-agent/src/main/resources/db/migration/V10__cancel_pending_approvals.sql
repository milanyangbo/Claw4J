-- 换键之后：还挂着的审批请求作废（ADR-0029）。
--
-- V9 把旧会话的历史清空了，而 pending 的审批行还在，它们记着的 `session_key` 指向的是
-- 已经不存在的那些段。留着它们的后果不是"报错"，是"点了没反应"：
--   · 批准 → 续跑要回到那一段 → 那一段没了 → 要么开一段空的（模型对着空气继续），
--     要么被 ApprovalService 的 GONE 分支挡下（结论只报给操作者）。
--   两条都不是提请求的人想要的，而他们看到的是同一句话："批准了，但没法接着做。"
--
-- 所以在这里一次性作废：让还挂着的人重新提一次，比留一堆点了没有结果的按钮好。
--
-- **只动 pending**。allowed / denied / expired 是审计事实——谁批的、谁拒的、什么时候超时的，
-- 换键不该把这些抹掉。它们留着的 `session_key` 指向旧形状的段，那是对的：它记的是当时的事实。
--
-- 终态用 cancelled + run-aborted，不伪造 timeout：
--   · 不是 timeout——没人等到超时，是那一段不存在了；
--   · 不是 expired——V6 的 CHECK 把 expired 的终态原因锁死成 timeout；
--   · cancelled 那一档允许 run-aborted / gateway-restart，语义是"那一轮不在了"，
--     与这里的实际情形最近。词表是照 OpenClaw 的 operator_approvals 抄的，不为了这一次
--     改它的取值——加一档要两边一起改，而这里表达得清楚。
--
-- 部署顺序：V9 清会话 → V10 作废请求。反过来的话，V10 跑完 V9 又清一次，结论一样，但
-- 日志里那次"作废 N 条"会与"清了 M 段"分开两次出现，排查时对不上。

update claw4j_run.approval_request
   set status = 'cancelled',
       decision = 'deny',
       terminal_reason = 'run-aborted',
       resolved_at = now(),
       resolver_kind = 'system',
       reason = '会话键换了形状，发起请求的那一段已经不在了（V9）'
 where status = 'pending';
