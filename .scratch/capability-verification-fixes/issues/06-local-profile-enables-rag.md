# 06: 本地 profile 默认带知识库，重排默认开

**What to build:** `application-local.yml.example`（模板，随仓库走）与本地那份 `application-local.yml`
都加一段 `claw4j.rag`，`enabled: true`、`directory: examples/knowledge`。起
`--spring.profiles.active=local` 时**不用再敲任何 `--claw4j.rag.*`**，知识库就在。

**Blocked by:** 票 01（`knowledgeReranker` 要 Jackson 2 的 bean，不修则默认开着必启动失败）。

**Type:** task（用户 2026-11-16 明确要求）

**Status:** resolved

**Demo:** 只带 `--spring.profiles.active=local` 起实例，启动日志里有
`知识库已启用：目录 examples/knowledge、…重排 开` 与 `知识库同步完成：…现有 2 段`。

## 为什么放 local 而不是主 `application.yml`

主配置里 `enabled: ${CLAW4J_RAG_ENABLED:false}` 是刻意的：别的部署没有 embedding 凭据、也没有
pgvector 那张表，默认开等于默认起不来。而 local profile 两样都有（compose 起的 pgvector +
`.env` 里的 qwen key），"本地能开就开着"才符合它的定位——它本来就是**这台机器决定装什么**的地方
（MCP 那几个 server 也在这里）。

`application-local.yml` 本身被 `.gitignore` 忽略（可能放真实 key 与真实的人），所以模板文件才是
随仓库走的那个；两份都要写，否则"照模板复制一份"的人拿不到知识库。

## 要改的文件

| 文件 | 改什么 |
| --- | --- |
| `claw4j-app/src/main/resources/application-local.yml.example` | MCP 那段之前加 `rag:`（含为什么默认关在主配置、这里覆盖的说明） |
| `claw4j-app/src/main/resources/application-local.yml` | 同上（本机实际生效的那份，不入库） |
| `README-adding-capabilities.md` §7 | 补一句"本地什么都不用配"，并把"重排有没有真跑"的判据写成 DEBUG 那一行 |

## 实现记录

- 两份都加了 8 行注释 + `enabled: true` / `directory: examples/knowledge`；重排不写，跟默认值（开）走。
- 真实例（只带 `--spring.profiles.active=local`）：8 秒 UP，日志
  `知识库重排已启用：…（模型 qwen3-rerank）`、`知识库已启用：… 混合检索 开、重排 开`、
  `知识库同步完成：2 份文档里重建 0 份、跳过 2 份…现有 2 段`，同时 `MCP server 状态：demo/github/mail → UP`。
- 问「华南区退货需要原包装吗，超过几天要找主管？」：答里带 `[1]`，且 DEBUG 日志出现
  `重排完成：候选 2 条，服务返回 2 条，前三条 […] → […]`——响应被解析、顺序被应用过，
  没有 `重排失败` 的 WARN。
