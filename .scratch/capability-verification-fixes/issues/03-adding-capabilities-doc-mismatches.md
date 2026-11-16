# 03: 操作手册里有三处说法与实现不符

**What to build:** 把 `README-adding-capabilities.md` 与 `docs/capability-tests.md` 里三处"照做会白费
一次"的说法改成实测事实。只改文字与判据，不动代码。

**Blocked by:** 无（与票 01 无关；票 02 若定出根因，其判据可能再改一次第 6 节）。

**Type:** docs

**Status:** ready-for-agent

**Demo:** 一个新用户照第 1 节做完 jira 工具，按文档去启动日志里找那行 `工具档位 messaging：…` ——
找不到。改完之后他按文档能找到真正能判定的那个数。

## 一、`README-adding-capabilities.md:84`：那行日志默认不出现

现在写：

> 启动日志里那行 `工具档位 messaging：清单 N 个，挡掉 M 个（…）` —— `N` 应该比之前多 1

实际是 `AgentAutoConfiguration#report`：

```java
if (selection.droppedAnything()) { log.info("工具档位 {}：清单 {} 个，挡掉 {} 个（…）", …); }
```

**只在真有工具被挡掉时才打印。** 默认档位 `full` 什么都不挡 → 刚加一个工具时这行根本不出现，
用户照着找会找不到。改成：

- 判据用计数：`curl -s localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool=jiraSearchIssues`
  （问一句只有它能答的话之前是 404，之后有值）；
- 那行日志降级成"**只在有工具被挡掉时**出现"，并说清它答的是"这个部署挡了什么"，不是"我加成了"。

## 二、第 3 节脚本包：没写"参数按包目录内的相对路径解释"

`ScriptPackageRunner#tokenOf` 对**每一个**参数都过 `SandboxPolicy#resolveInside`，再把结果
`relativize` 成包内相对路径。于是：

- 绝对路径 `/tmp/x.md` → `SandboxViolationException:路径参数必须是相对路径…`；
- 包外相对路径 `../../docs/a.md` → `…越出了脚本包自己的目录`；
- 普通字符串 `深圳` → 原样送达（包内不存在同名文件时就是它自己）；
- **含斜杠的 URL 会被改写**：`http://a/b` 变成 `http:/a/b`（`Path` 规范化）——这条要写进文档，
  并在 `examples/scripts/summarize/package.properties` 的描述里去掉"给它一个 http 地址"，
  因为它实际走不通（是否要修 `tokenOf` 属于另一张票，本票只负责不再宣传它）。

加一句"要读文件就把它放进包目录，用包内相对路径"。

## 三、`docs/capability-tests.md` 第 6 节：期望值缺一半

现在写"派一个子 Agent 出去" → "每次派完 `hit Task` +1"。缺的是**前提**：这套装配里模型一开始
只看得见 `toolSearchTool`（`ToolSearch.SYSTEM_SUFFIX`），所以"派活"是两步——先检索出 `Task`，
再调用它。写清这一点，否则"零计数"会被误判成"功能坏了"（本次实测就是这么误判的，见票 02）。

## 要改的文件

| 文件 | 改什么 |
| --- | --- |
| `README-adding-capabilities.md` | 第 1 节"怎么确认"换成计数判据；第 3 节加参数解释规则 |
| `examples/scripts/summarize/package.properties` | 描述去掉 http 地址（那一路被 `tokenOf` 改写） |
| `docs/capability-tests.md` §6 | 补"先检索再调用"这个前提，与判据放在一起 |

## 验收判据

文字类改动，不写用例。判据是**照着做一遍能对上**：

1. 按第 1 节的计数判据去查，刚启动（还没问话）时 curl 该指标返回 404，问完之后返回一个数；
2. 按第 3 节把文件放进包目录、用包内相对路径调用，能拿到摘要（本次实测已验过一次）；
3. §6 的"先检索再调用"与 `ToolSearch.SYSTEM_SUFFIX` 的措辞一致，不引入第二套说法。
