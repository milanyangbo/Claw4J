# 04: 子 Agent 示例多几个场景，并且真的被读一遍

**What to build:** 把 `examples/subagents/` 从"两份定义"扩到"覆盖不同委派形状的一组"，
再补一条用例：**真的用 `SubagentCatalog.load()` 读这个目录**，于是"文档里写着配这一行就能用"
不再是一句没人验过的话。

**Blocked by:** 无（03 已给出第一份定义与格式约束）。

**Status:** resolved

## 为什么

03 号票收尾时自己记了一条债：`capacity-triage.md` 只做过**格式对照**，
没被加载器读过。而 `SubagentCatalog` 的判据很硬——目录不存在、目录空、某份定义解析不出，
**一律启动失败**。一份写错的定义不是"少一个子 Agent"，是整个应用起不来。

还有一半是覆盖面：现在的两份定义（`capacity-triage` 白名单一个工具、`sql-reviewer`
白名单一个工具）都是"只准用一个工具"的形状。"继承全部工具"这条路、`disallowedTools`
这条路、以及"委派工具自己要不要出现在子那侧"这条不变量，在示例里都没有对应的文件。
示例是给人照抄的，只给一种形状，抄出来的都是那一种。

## 要做

- [x] 补三份定义，各自代表一种**不同的委派形状**：
  - `incident-triage.md`：`tools` 空（继承全部），靠 `disallowedTools` 减掉会改东西的那些
  - `weekly-digest.md`：白名单跨层（脚本包 `summarize` + Skill），演示"子 Agent 不只是查库"
  - `field-mapping.md`：只读、只给 `systemStatus`，演示"排除法"——回答"这个值在哪读"，
    不碰业务数据
- [x] 新增 `ExamplesSubagentsTest`（`claw4j-agent`）：
  - 用 `SubagentCatalog.load()` 读**仓库里真实的** `examples/subagents`（不是 `@TempDir` 里造的）
  - 断言：全部能解析、`name` 与文件名一致、`description` 不空、正文不是占位
  - 断言：每份定义的白名单/黑名单里的工具名，都在**示例装配能提供的工具集**里
  - 断言：`selectTools` 对"继承全部"的那份把委派工具自己减掉了（委派不嵌套）
- [x] `examples/README.md` 与 README 的 subagent 一节跟着改成真实的一组。

## 判据

- 用例在 `claw4j-agent` 里跑得通，且**不依赖数据库、不依赖网络**。
- 它抓得住两类真实错误：定义里工具名拼错（白名单点名了不存在的工具）、
  `name` 与文件名不一致（委派失败时按名字找文件会找错）。
- 目录定位从 `user.dir` 往上找，Maven（模块目录）与 IDE（仓库根）两种工作目录都成立。

## 不要做

- 不为测试在仓库里再存一份定义副本：测的就是 `examples/` 里那一份，
  复制一份等于让"漂移"重新变成可能。
- 不在用例里跑真模型：这一层验的是**加载与边界**，真模型委派由 `ApplicationAssemblyTest` 那条覆盖。
- 不给每份定义都塞一长段提示词：示例的价值在形状，不在字数。

## 实现记录

### 落地

- 新增 `incident-triage.md`（继承全部 + `disallowedTools`）、`weekly-digest.md`
  （跨层白名单：`summarize` + `Skill` + `querySales`）、`field-mapping.md`（只读单工具）。
  加上原有的 `capacity-triage.md` 与 `sql-reviewer.md`，一共五份，覆盖"白名单单工具 /
  白名单跨层 / 继承全部 + 黑名单"三种形状。
- 新增 `claw4j-app/src/test/java/io/github/claw4j/app/ExamplesSubagentsTest.java`，5 条用例。
  放在 app 而不是 agent：这条验的是"**示例素材**能不能被装配进来"，而 app 是唯一同时看见
  agent 与示例插件的模块；agent 的测试目录另有约束（文件数上限 10，加第四个文件会顶上限）。

### 工具名那份清单是怎么来的

用例里有一份 `SAMPLE_TOOLS`（`systemStatus` / `querySales` / `summarize` /
`weather-forecast` / `xurl` / `Skill` / `Task`）。它不是从容器里取的——
`claw4j-agent` 的测试装配里没有 `claw4j-example-sales`，也没有配脚本目录。
所以它是**手抄的一份**，注释里写明了它的来源与"改了示例工具要同步这里"。
这是这条用例唯一的软处：它抓得住"定义里拼错了一个名字"，
抓不住"示例应用的装配真的少了那个工具"——后者由 `ApplicationAssemblyTest` 覆盖。

### 为什么不用 `@TempDir`

同环境有关，也与判据有关：要抓的是**仓库里那一份**写坏了，用临时目录造的副本
永远是对的。读真实路径反而是这条用例存在的理由。
