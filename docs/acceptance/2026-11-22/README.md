# 验收材料（2026-11-22 这一批：G 自管理 / H 流式 / 演示剧本）

对应文档：仓库根 `README-capability-tests.md` 的 **G. Agent 自管理（票 01–03）**、**H. 流式（SSE）**，
以及 **`README-demonstration.md`**（三场戏）。
这里的文件都是为了让你自己能一条条复跑，不是产物。

**这是一批，不是唯一一批**：日期只是"哪天跑的"标签，明天照常跑；要留新一批就照
[../README.md](../README.md) 开当天的目录，不会覆盖这里。

**位置**：本目录在仓库里（`docs/acceptance/2026-11-22/`），下表路径都相对本目录。
起实例时人在仓库根，所以命令里挂目录的参数要写全（`docs/acceptance/2026-11-22/bigagent`）。
`*.log` 不进库（见根 `.gitignore`），其余留在版本里当证据。

## 材料清单

| 路径 | 是什么 |
| --- | --- |
| `bigagent/big.md` | G3 用的超长人设（22430 字符，正文过 20000 的单段上限）。`tier: full` 是必须的，否则工具画像会被收窄 |
| `g-app.log` | G1/G2 那一轮的正常实例日志（含迁移、装配、出站回答） |
| `g-big.log` | G3 那一轮的日志，`提示词超预算已截断` 那行在里面 |
| 库 `claw4j_accept_g` | 跑过一轮的库（10 个会话节点、进度卡表已清、意图表已清）。想从零开始就照文末 drop 重建 |
| `h-app.log` | H1–H3 / H5 那一轮（带 `--claw4j.agent.lane-wait=0`） |
| `h-404.log` | H4 那一轮（主模型名写错），`流式失败（unclassified，未吐内容）` 那行在里面 |
| `h-timeout.log` | H6 那一轮（`--claw4j.channel.http.stream-timeout=2s`），`AsyncRequestTimeoutException` 在里面 |
| `model-404.yml` | H4 用的那份覆盖配置 |
| `stream-*.txt` | 每一轮的原始 SSE 帧（basic / tool / new / elevated / busy-b / failover / timeout） |

`bigagent/big.md` 丢了就照文档 G3 那段 python 重生成，内容一样（900 条占位行）。

## 起实例

正常那一版（G1/G2）：

```bash
cd /Users/ethanbyang/projects/java/Claw4J
set -a; . ./.env; set +a
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/claw4j_accept_g \
  --claw4j.advisor.session.dm-scope=per-channel-peer \
  --claw4j.channel.http.users='*' \
  --claw4j.skills.directory=examples/skills \
  --claw4j.subagents.directory=examples/subagents \
  --claw4j.agent.directory=examples/agents --claw4j.agent.default=ops \
  --claw4j.advisor.cache.enabled=false
```

G3 那一版只改人设目录与默认人设（其余不动）：

```
  --claw4j.agent.directory=docs/acceptance/2026-11-22/bigagent --claw4j.agent.default=big
```

两版不要同时起（都抢 8080 / 9090）。等就绪：

```bash
curl -s --noproxy '*' localhost:9090/actuator/health/readiness
```

## 查库（本机没有 psql，走容器）

```bash
docker exec -i claw4j-postgres psql -U claw4j -d claw4j_accept_g -c \
  "select session_key, revision, steps_json from claw4j_session.session_progress_cards
    order by updated_at;"

docker exec -i claw4j-postgres psql -U claw4j -d claw4j_accept_g -c \
  "select id, owner, trigger_keywords, status, fire_count, max_fires, cooldown_seconds,
          channel_scope, sender_scope, trigger_embedding is null as emb_null
     from claw4j_session.standing_intents;"
```

## 上一轮实测到的值（复跑会变，只用来对形状）

| 项 | 值 |
| --- | --- |
| 进度卡 session_key | `claw4j:user:td1:http:http:card-1:1795348016149-e212`（`revision` 2，三条全 COMPLETED） |
| `/new` 之后 | 后缀换成 `…-50c7`，新卡 `revision` 从 1 起 |
| 清空待办之后 | 同一行、`revision` +1、`steps_json` 为 null |
| 意图 | `cb6c8264 / it1 / 客户数 / armed / 0 次 / 上限 3 / 冷却 86400 / http / it1 / embedding null` |
| 命中后 | `fired`、`fire_count` 1；换一个 chat 再问，仍是 1（冷却内） |
| 撤销后 | `cancelled` |
| 预算 WARN | `提示词超预算已截断：22479 → 20106 字符（上限 60000），稳定段 20000 字符`，全程只有一行 |

## 收掉

```bash
docker exec claw4j-postgres psql -U claw4j -d postgres -c "drop database if exists claw4j_accept_g;"
```

## H 流式：三个实例各管一条

| 验什么 | 起实例时加的参数 |
| --- | --- |
| H1–H3（分帧、命令、工具） | 正常那一版即可 |
| H5（忙） | `--claw4j.agent.lane-wait=0`（默认 60 秒是排队，验不到忙） |
| H4（换家） | `--spring.config.additional-location=file:docs/acceptance/2026-11-22/model-404.yml` |
| H6（断流） | `--claw4j.channel.http.stream-timeout=2s`，让它写长文 |

上一轮实测到的值：

| 项 | 值 |
| --- | --- |
| H1 基础流 | 79 chunk + 1 done，first 1.73 / total 2.17 |
| H2 `/new` | 1 chunk（"好，这是一段新对话…"）+ 1 done，`model.calls` 不变 |
| H3 带 Read | 45 chunk，first 2.25；流里搜不到工具名/JSON；`tool.calls{Read}` 1 |
| H4 换家 | 回答 `2` + 1 done；日志 `流式失败（unclassified，未吐内容），换下一个` |
| H5 忙 | 1 chunk（"现在有点忙，请稍后再问一次。"）+ 1 done |
| H6 断流 | 32 chunk、**0 done**、总耗时 2.1 秒 |

`grep -c '^event:done' stream-*.txt` 可以直接数每一轮有几个结束帧。

## 演示（README-demonstration.md）留下的东西

| 路径 | 是什么 |
| --- | --- |
| 库 `claw4j_demo` | **带数据**，不要 drop：1 条嘱咐（触发 1 次）、1 张进度卡、2 条定时任务、6 条审批、158 条审计 |
| `demo-app.log` | 三场戏跑完的完整日志（含审批、子代理策略、cron 自己跑那一行） |
| `demo-a2.txt` | A2 那一段流式原始帧（161 帧 + 1 done） |
| `demo-c1.txt` | C1 那一段流式原始帧（283 帧 + 1 done） |

起演示实例的命令在文档 0 节，四个参数不能少：`exec.ask=on-miss`、`elevated.enabled`、
`cache.enabled=false`、指向 `claw4j_demo`。

实跑记下的几个数（重跑会变，只对形状）：

| 项 | 值 |
| --- | --- |
| A1 嘱咐编号 | `4bafeb6e`，armed，上限 3 次 |
| A2 命中 | 161 帧 / 1 done，`fire_count=1` |
| A3 查单 | `querySales ok` 1 次；SO-LAST-02 SHIPPED 74400，SO-LAST-03 CANCELLED 50000 |
| A4 审批 | `9dd4511c` 批准后 `consumed_at` 非空，会话里出现"已按批准的原命令执行完毕（EXIT=0）" |
| A5 子代理 + cron | `Task ok` +1；`b2d97454`（`0 */2 * * * *`）21:04:30 自己跑完，审计 4 条 cron 段 |
| B1 周报 | 128400 / 26000 / 31500 / 185900 |
| B5 周任务 | `ad3a04ba`，`0 0 9 * * MON`，下次 2026-11-23 09:00 |
| C2 采集 | 8080 http=404（在听）、9090 health=UP、8082 http=000 |
| C3 提权 | "这一段跳过审批（到 21:36:35 自动失效）"，之后无新审批 |
| C4 审计 | Bash 4 成功 / 6 失败；四对成功里三对带 `approval_id`，提权跑的那对没有 |
