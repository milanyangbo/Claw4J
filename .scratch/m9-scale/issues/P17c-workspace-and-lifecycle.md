# P17c: 工作目录进出、容器复用与退出清理

**What to build:** 假设命令已经能跑进容器（今天**不成立**：P17a 于 2026-11-18 整体回滚，见下），
但没说清楚三件决定它能不能日常用的事：

1. **宿主的目录怎么进容器**（`workspaceAccess`: `ro` / `rw` / `none`）
2. **容器活多久**（`scope`: `session` / `agent` / `shared`）
3. **谁来收尸**（进程退出、异常、超时之后，容器还在不在 `docker ps -a` 里）

**Status:** blocked（2026-11-18 重估：三件事今天都不在我方手里，与 P17b 同一个上游；
另外 P17a 已回滚，连"命令进容器"这一步都要重做）

> **这一票在 2026-11-18 被重写过一次**。原稿假设我们自己拼 `docker run`（`ContainerSpec` +
> `DockerSandboxRunner` + `SandboxSessionRegistry`），那套东西随票 02（脚本包退化成技能）整包删了。
> 现在容器只有一个来源：库的 `DockerCliExecBackend`。于是三件事的归属变了：

| 这件事 | 今天归谁 | 卡在哪 |
| --- | --- | --- |
| 挂载怎么给（`ro` / `rw` / `none`） | 库：`DockerCliExecBackend.Builder#mount(Path, String)`，**只有读写** | `ro` / `none` 要库加参数 → 与 P17b 同一个上游 |
| 容器活多久 | 库：一个 `DockerCliExecBackend` 实例自带一个托管容器（`sleep` 保活），进程级 | `scope=session/agent` 要按会话建多个后端并回收，库没有这个接缝；自己管等于再写一层容器编排（票 02 刚删掉的那类） |
| 谁来收尸 | 库 + 进程退出：容器是 `--rm` 吗？`build()` 失败时留不留？ | 要确认库的语义；"JVM 被 kill -9 之后清残留"需要跑 `docker ps`，而**本仓已经不自己起进程了**（零例外），要做得先决定这条豁免 |

**所以今天能做的只有三件小事**（等 P17b 上游松动时一起做，不做就先记着）：

1. 确认库的两条语义并写进文档：容器是不是 `--rm`、`build()` 抛异常时容器会不会留下；
2. `workspace-access=ro|none` 在 `mode≠off` 时由 doctor 报一条 FAIL（**已做**：P18a 的
   `ConfigSanityCheck`，见该票"配置组合"），别让配置假装有效；
3. 真要做"崩溃残留清理"，得先给"读一次 `docker ps`"开一个受控豁免（只读、argv、不拼命令行），
   那是**一张单向票**，不该顺手塞进本票。

**裁决来源仍然成立**：ADR-0030 §裁决六（`scope` / `workspaceAccess` 的取值与"`shared` 时丢弃
per-agent 覆盖"）、ADR-0020 §M9 · P17（临时 workdir）。它们描述的是**要达成的结果**；
今天缺的是"用什么接缝达成"，不是"要不要达成"。

## 解锁条件（写清楚，免得到时候又从头想）

P17b 回来时（上游给 `DockerCliExecBackend.Builder` 加了限额与网络参数），本票按下面顺序做：

1. `ro` / `none` 两档挂载：从库的新参数直接给，**不自己拼 `docker run`**；
2. `scope`：先只做 `shared`（= 今天的行为，进程一个容器），`session` 单独开票——
   它要库支持"按 key 建/取后端"，那是库的接缝问题；
3. 残留清理：只有在第 2 条也需要"查容器"时才有必要一起做；否则不做。

## TDD 用例清单（解锁后再填）

原稿那九条（`WorkspaceAccessTest` / `SandboxSessionRegistry` 复用与引用计数 / `destroy()` 无残留）
**整体作废**：它们的对象是自研的容器编排，那套东西已经不在了。解锁时按第 2 条的顺序重写，
每一条都要能落在库的公开接缝上，落不上的先记进"缺口"。

## 缺口（继承原稿，逐条核过）

- **`scope=shared` 的 per-agent 覆盖丢弃**：我们是单层配置，没有 `agents[].sandbox.*`，**没有对象**。
- **崩溃残留只能"下次清"**：要跑 `docker ps`，与"零例外不起进程"冲突，见上。
- **不做容器预热池**：冷启动成本先认了。
- **`elevated` 不在本票**：它依赖 P17 全部落地 + 票 06（`.scratch/permission-alignment/issues/06`）。
