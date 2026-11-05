-- 一条 run 当初在哪条"对话"里（ADR-0026 决定二）。
--
-- 与 session_id 是两个键，回答两个问题：
--   session_id      哪一段会话（会随段轮换而变，用于记忆与预算）
--   session_address 哪条对话（不随段变，用于抢那把 lane 锁）
--
-- 为什么要多存一份：审批续跑那条路没有入站消息，手上只有 session_id 那一串
-- `claw4j:user:<owner>:<通道>:<chatId>:<段>`。反解要按 `:` 切，而 chatId 里出现
-- 冒号是可能的（飞书的 oc_xxx 不带，自定义通道不受约束）——解析是脆的，
-- 而它错的方式是"锁到了另一条对话上"，不报错。
--
-- 老 run 没有这一列：resume 读到空就不加锁，并记一条 debug。那条路上只有审批
-- 能推动它，放弃执行比如上一把抢不到的锁更糟。
alter table claw4j_run.agent_run add column if not exists session_address text;

comment on column claw4j_run.agent_run.session_address is
    '这段对话的地址（addr:<owner>|<通道>|<chatId>）：不随段轮换变，用来按对话串行化';
