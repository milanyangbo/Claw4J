# 对齐：外部能力怎么用

**一句话**：外部有对应物的能力，就用它本来的样子。它的缺口不补，我们的不变式给它让路。

**ADR 不是豁免**（2026-11-18 加）：本项目自己的 ADR 与 OpenClaw 的机制冲突时，**以 OpenClaw 为准**——
改 ADR，不改对齐目标；"它没有这个形态"也不算理由，要问它用什么达成同一件事。
规则与对照表的要求写在 `AGENTS.md`「设计一律照 OpenClaw」。

这一份是判据，不是清单。清单会过期，判断方式不会。

## 三条判据

1. **它没有的能力，不补。**
   缺了就记进"不接受的风险"，不写代码补。补出来的每一处差异，都是下一次切换时要拆的东西。

2. **它和我们的不变式冲突，改不变式，不改它的实现。**
   不加包装层去吞它的异常、不加 try/catch 去维持我们的承诺、不绕开它的生命周期、不换它的表。

3. **每加一个类 / 一列 / 一条 SQL，先回答"它哪个部件对应这个"。**
   答不上来就是自研。

## 2026-11-09 的反例：语义缓存

用户要求"框架有 `SemanticCacheAdvisor` 就用"。我切了，但同一批改动里还留着七样官方没有的东西：

| 加的东西 | 我当时的理由 | 官方有对应物吗 |
| --- | --- | --- |
| `entities` 列 + 实体签名 | 实测「上周/上上周」余弦 0.9950，阈值分不开 | 没有。它的 `contextHash` 只有系统提示词一个来源 |
| 命中标记「（同上次）」 | 用户分不清答得快是缓存还是模型 | 没有 |
| `hits` / `misses` 指标 | 要知道命中率 | 没有 |
| 副作用判定（`sideEffectFree` + `ToolCallLog`） | `run_script` 那一轮的答案被复用等于复用一次执行结果 | 没有 |
| `expires_at` + 清理任务 | TTL 只是一行时间戳，不清理表会一直长 | 没有（它的 `ttl` 字段是死代码） |
| 唯一键 + upsert | 同一问题问一百遍会躺一百行 | 没有 |
| 两条诊断查询 | 分清 miss 是换权限还是换时间窗 | 没有 |

**每一件我都能给出理由，问题就出在这**：理由成立证明的是"我这家实现更正确"，
不是"它本该如此"。七处差异，切到官方实现时全部变成要拆的代码；而我还想再加一层
try/catch 去维持"缓存不参与可用性"——那是拿自研代码给自研承诺续命。

**判据的顺序错在哪**：我先有"缓存是优化，不能影响可用性"，再拿这条去评判官方实现，
发现它冲突，于是打算给它加壳。正确顺序是先看它的形状（`adviseCall` 没有 try、
索引只在 `afterPropertiesSet` 里建、`ttl` 字段没人写），再问"我们的哪条承诺与它冲突"——
冲突的那条改掉。

## 动手前的四问

1. 这个东西的默认形态是什么？（配置项 / 默认值 / 一个能跑的例子，不是文档的形容词）
   答不上来就是**没查**，不是"我记得它是这样"：给出 `文件:行`，或者给出查过的命令与命中数。
   OpenClaw 的判据是 `a fix that cannot name the line where the bug shows has an unverified premise`
   ——**指不出行的结论还是前提，不能当事实写进 ADR**。2026-11-09 就有一条假事实进了 ADR
   （"OpenClaw 全仓 rerank 0 命中"，实际是 Bash 里那个玩具 grep 对 `\|` 恒返回 0）。
2. 我打算加的每一样，它哪个部件对应？没有对应物的，删。
3. 冲突的是哪条承诺？那条承诺能不能改？不能改再谈"照不了"。
4. 改完之后，"我们和它不一样的地方"能不能一句话说完？超过一句，就是又发散了。

第 3 问答"不能改"时，走 `AGENTS.md` 里那条三段式（它那条机制是什么 → 我们差在哪 →
只能改成什么、代价是什么），等人判断，不自己拍。

## 接口实现不是自研（2026-11-09 知识库）

判据 1 说的是"它没有的**能力**"，不是"它的接口没有**实现**"。两者在 diff 上很像，
区分只有一个问题：**这个类实现的是不是框架定义的接口？**

| 写法 | 判定 |
| --- | --- |
| `RankFusion`（上一版删掉的那个）：自有类、自有签名、没有调用点 | 自研 |
| `WeightedScoreJoiner implements DocumentJoiner` | 实现（融合公式照 OpenClaw 的 0.7/0.3） |
| `RerankDocumentPostProcessor implements DocumentPostProcessor` | 实现（接口在，官方零实现，槽位是官方留的） |
| `TopKDocumentPostProcessor implements DocumentPostProcessor` | 实现（同上。它补的是"框架没有最终截断部件"这个空位，不是自造概念——OpenClaw 同一位置也是 `slice(0, maxResults)`） |
| `SemanticTextSplitter extends TextSplitter`（ADR-0028） | 实现（扩展点是 `splitText`） |

判据 3 的答案就是**接口名**。答得出接口名，并且调用点是框架留的槽位，就是实现；
答不出，就是自研。

## 先找现成的实现（2026-11-18 容器沙箱）

判据 1 与「接口实现不是自研」管的是"怎么写"。这一节管**更前面一步：要不要写**。

**顺序是固定的**：本项目已引的库 → Spring AI / `spring-ai-community` 的官方模块 → OpenClaw
的对应机制 → 其它成熟开源。找过之后才允许自己写，票面写清"找到什么、为什么不用"。

**"找过"的判据是打开 jar**：`unzip -l` 看有没有这个类、`javap` 看它的 API 与默认值、
读它的 caveat。不是"我记得它没有"，也不是"文档里没提"。

### 反例：P17a 的 docker 沙箱

我先写了三样：拼 `docker run` 的 argv、启动期探测、后台句柄。用户一句"看看 spring-ai 社区
有没有相关的实现"，`spring-ai-agent-utils` 里就有
`org.springaicommunity:spring-ai-agent-utils-docker-cli` 的 `DockerCliExecBackend`：

| 我当时自己写的 | 库里已有的 |
| --- | --- |
| 拼 `docker run` 的 argv + `--volume` / `--workdir` / `--env` | `builder().image(..).mount(..).containerWorkingDirectory(..).environment(..)` |
| 启动期探测（`docker info` + `image inspect`） | 托管容器在 `build()` 时**直接建起来**，起不来就 fail-fast，比探测更硬 |
| 后台句柄（自己起进程、自己收流） | `ExecHandle` 在 `docker_` 命名空间，游标语义与本地一致 |
| 超时=杀掉 docker 客户端 | **每条命令在容器内记 PID**，超时在容器里发 TERM 再 KILL —— 这一条我根本没处理（杀了客户端容器里的进程还活着），而它是 Docker 的已知限制 |
| 容器回收 | `close()` + JVM shutdown hook + label（崩溃后 `docker rm -f $(docker ps -aq --filter label=..)`） |

自己那版唯一"更对"的地方是脚本层的 argv 不被解释器再看一遍——但那不是它没能力，
是**两层的请求形态不同**：`ExecSpec` 是一条命令字符串（exec 要的），脚本层的参数必须是数组。
当时的分界是：**exec 用库的 `DockerCliExecBackend`，脚本层保留自己的 argv 实现**。

**第三次裁决（2026-11-18 晚）：P17a 留下，限额等上游，这一节的反例要往后读一层。** 换上库的后端之后，
真正的问题暴露出来：`DockerCliExecBackend.Builder` 只有 7 个入口，**没有任何限额参数**——
`network` / 只读根 / 内存 / pids / user / cpus 一个都没有。于是命令能送进容器、却关不住它。
裁决是三分：**P17a 做**（容器能跑）、**P17b / P17c 等上游**（不自己实现、不绕）、P18 回滚。

这条比"先找现成实现"更硬：**找到现成实现之后，还要问它能不能表达我们的约束**（`AGENTS.md`
四道筛的第 4 题）。答不上来时正确的动作是**停下并把票标 blocked**，不是自己接一条替代路
（拉一份 compose 再 `containerId(..)`、换 docker-java、自己拼 `docker run`）——
那些都是新的架构决定，得人来拍。这一条已写进 `AGENTS.md`「卡住就停」。

**这一条也顺带纠正了一个旧习惯**：本项目手写的 `ShellCommandRunner` / `LocalProcessRunner`
同样早于库里的 `LocalExecBackend`。2026-11-18 已把这四个包（`process` / `sandbox` / `exec` /
`script`）逐件对着源码核过一遍，结论与判据在 `docs/reviews/2026-11-18-open-source-inventory.md`：

- `ShellCommandRunner` / `ShellProcess` → **已换**成库的 `LocalExecBackend`（2026-11-18，
  `.scratch/oss-alignment/issues/01-exec-host-backend-to-local.md`）；
- `LocalProcessRunner` → 换成 Apache Commons Exec（zt-exec 的 `applyEnvironment()` 只能增删单个键，
  表达不了"环境只有 PATH"这条约束）。**后来不需要了**：脚本层按 OpenClaw 的形态退化成技能，
  这条 argv 通道整包删掉（同一天第二张票）；
- 命令白名单（`ExecPolicy`）：OpenClaw 有对应物（`security=allowlist` 按管道段判），我们见控制符一律拒，
  比它严，属偏离，已记账；
- 容器 argv 与路径边界：随脚本层一起删掉，不再有"要不要换"这个问题。

**这两张票都做完了**（`.scratch/oss-alignment/issues/01`、`02`），别再当成"还没定"。

### 质量门槛与两个判例

四道筛（使用面 / 维护面 / 代价 / 能不能表达我们的约束）写在 `AGENTS.md`「开源的质量怎么判」，这里只记判例：

- **164★ 胜过 919★**：`commons-exec` 走 `Runtime.exec(cmdarray, envp, dir)`，envp 整份替换，
  正好表达"环境只有 PATH"；`zt-exec` 的 `applyEnvironment()` 只能增删单键。星数只负责先筛掉没人用的。
- **形状最对的反而不能引**：`markpollack/agent-sandbox` 1.0.0 的 `ExecSpec` 每个后端都是 argv，
  正是脚本层要的，但 3★、个人仓、1.0.0 刚发（2026-11-15）、且没有限额参数——只看，不引。

## 这条同样适用于 OpenClaw

OpenClaw 不是"参考实现"，是**判据来源**。它的机制优先于"这样也说得通"；
它的缺口（比如没有语义缓存）不构成"我们自己造一个"的许可——那种能力要么不做，
要么用别的官方对应物，要么明确记账后等人决定。
