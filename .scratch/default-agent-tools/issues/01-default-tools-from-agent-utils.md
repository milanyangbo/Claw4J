# 01: 把 agent-utils 的只读工具默认装进 agent

**What to build:** `Read` / `Grep` / `Glob` / `TodoWrite` / `WebFetch` / `WebSearch` 六个工具
启动即进清单，不需要任何配置；`Write` / `Edit` 由 `claw4j.tools.files.write-enabled=true` 单独打开；
`WebSearch` 由 `BRAVE_API_KEY` 的有无决定装不装。工具实现一行不写，全部来自
`org.springaicommunity:spring-ai-agent-utils:0.13.0`。

**Blocked by:** 无。

**Type:** 新增默认工具（配置 + 装配 + 档位/权限登记）

**Status:** resolved

**裁决来源：** 2026-11-16 用户当场拍定三件：文件工具「只读三件套默认开，写要配开关」；
WebSearch「没 key 就不装配」；`Tar`/`AskUserQuestion` 两件另立票（02 / 03）。

## 现场

用户列了 11 个 `spring-ai-agent-utils` 的东西要「作为默认 tools 启动就注入」。逐个对 0.13.0 的
jar 核过，其中四个和现状对不上，记在这里免得下次再挖一遍：

| 用户列的 | 实际 | 现状 |
| --- | --- | --- |
| `ShellTools` | `Bash` / `BashOutput` / `KillShell` | **早就是默认的**：`ExecAutoConfiguration`，`mode=full`，外面套 `SandboxedExecBackend` |
| `SkillsTool` | `Skill` | 已接（`AgentSkills`），但 `claw4j.skills.directory` 默认没配，所以装配不出来 |
| `AgentEnvironment` | **不是工具**，是 `info()` / `gitStatus()` 静态方法，往系统提示词里拼工作目录与平台 | 没有；要用得改 `PromptComposition`，不在本票 |
| `TaskToolCallbackProvider` | **0.13.0 里没有这个类**；只有 `TaskTool` / `TaskOutputTool` | 仓库有自己的 `Task`，见票 03 |

真正要做的是 6 个工具 + 2 个开关。

## 修法

- `claw4j-capability` 新增 `builtin/agenttools`：
  - `FileToolsProperties`（`claw4j.tools.files`）：`directory`（默认 `.`，与 `exec.workspace` 同口径）、
    `write-enabled`（默认 false）。
  - `WebToolsProperties`（`claw4j.tools.web`）：`search-api-key`（默认空）。
  - `AgentToolsAutoConfiguration`：一个 `FileSystemTools` bean，用名字过滤拆成两个 provider
    （只读那支只导出 `Read`，写那支导出 `Write` / `Edit`）——`MethodToolCallbackProvider` 是按对象
    导出的，同一个对象上的三个 `@Tool` 分不开，只能导完再按名字筛。
- `claw4j-agent` 新增 `webtools/WebFetchAutoConfiguration`：`SmartWebFetchTool` 要一个 `ChatClient`
  做提炼，这里**现场建一个不带 Advisor、不带工具的**（`ChatClient.builder(chatModel).build()`），
  **不注册成 bean**：注册了就会有两个 `ChatClient`，`AgentAutoConfiguration` 的
  `@ConditionalOnMissingBean(ChatClient.class)` 会让位，主聊天客户端直接消失。
- `claw4j-agent/pom.xml` 去掉 flexmark 的 exclusion：`SmartWebFetchTool` 用
  `FlexmarkHtmlConverter` 做 HTML→Markdown，排除掉就是 `NoClassDefFoundError`。capability 那份
  排除保留，它那里没有使用者。
- 档位与权限（每个工具都要登记，`ToolProfile.select` 查不到档位直接启动失败）：

  | 工具 | 动作 | 档位 |
  | --- | --- | --- |
  | `Read` / `Grep` / `Glob` | `read_files` | `CODING` |
  | `Write` / `Edit` | `write_files` | `CODING` |
  | `TodoWrite` | `plan` | `MINIMAL` |
  | `WebFetch` / `WebSearch` | `web_fetch` / `web_search` | `CODING` |

  文件与联网都放 `CODING` 而不是 `MESSAGING`：`MESSAGING` 这条线的承诺是「只做消息与业务只读」，
  读服务器磁盘和出网都不是它。副作用是默认角色配置里只有 `admin`（`actions: ["*"]`）调得动它们——
  这正是有意的：`read-only` 拿 `read` 不该顺带拿到 `cat .env`。
- `application.yml` 的 `claw4j.tools` 下加 `files` 与 `web` 两段。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 默认就有 `Read`/`Grep`/`Glob`/`TodoWrite`，没有 `Write`/`Edit` | `AgentToolsTest#readOnlyToolsAreOnByDefault` | 把 write provider 的条件去掉 → `Write` 出现在清单里，red |
| `write-enabled=true` 才有 `Write`/`Edit`，且 `Read` 不重复 | `AgentToolsTest#writeToolsAppearOnlyWhenEnabled` | 只读那支不过滤 → `Read` 出现两次，red |
| 导出的每个工具都能在档位表与权限表里查到 | `AgentToolsTest#everyExportedToolIsRegistered` | 少登一个名字 → 断言点名那个工具，red |
| 目录外的绝对路径被拒，且不是抛异常 | `AgentToolsTest#pathOutsideTheDirectoryIsRefused` | 去掉 `allowedDirectory` → 能读到 `/etc/hosts`，red |
| 没 key 不装 `WebSearch`，有 key 才装 | `AgentToolsTest#webSearchNeedsAKey` | 条件恒真 → 无 key 时 `builder("")` 抛异常，red |

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 六个工具真进了清单 | 起 local 实例，启动日志里清单含这六个名字 |
| 模型真调得动 | `curl /api/chat` 让它用 `Glob` 找 `examples/**/*.md`，回答里出现真实文件名 |
| 写开关有效 | 不开时让它写文件 → 清单里没有 `Write`，模型说没有这个能力 |
| 门禁 | `scripts/verify.sh` 全绿 |

## 缺口

- `AgentEnvironment`（工作目录 / git / 平台）没做：它要改 `PromptComposition` 的段落结构，
  与这六个工具不是一件事。
- `AskUserQuestionTool` 与 `Task` 换成社区包的版本见 02 / 03，两票都卡在「对端不存在」上。
- 文件工具的目录边界只管 `..` 与软链接（库的 `AllowedDirectories` 三道检查），**不管内容**：
  `.env`、密钥文件都读得到。这与 `Bash`（`exec_command`，同样只给 admin）同一档暴露，不是新增的口子。

## 实现记录

- 新增 `claw4j-capability` 的 `builtin/agenttools`：`FileToolsProperties`（`claw4j.tools.files`
  的 `directory` / `write-enabled`）、`WebToolsProperties`（`claw4j.tools.web.search-api-key`）、
  `AgentToolsAutoConfiguration`。`FileSystemTools` 一个实例，按名字筛成两个 provider
  （只读那支只导出 `Read`，写那支只有开了开关才有 `Write` / `Edit`）。
- `claw4j-agent` 新增 `webtools/WebFetchAutoConfiguration`。**提炼用的 `ChatClient` 现场建、不进容器**：
  注册成 bean 会有两个 `ChatClient`，`AgentAutoConfiguration` 的 `@ConditionalOnMissingBean` 让位，
  主客户端静默消失。
- 档位与权限按上表登记；`application.yml` 加 `claw4j.tools.files` / `claw4j.tools.web` 两段；
  `application-local.yml{,.example}` 把 `claw4j.skills.directory` 指向 `examples/skills`
  （基础 yml 不配：别的部署没这个目录，配了空目录会启动失败）。
- `claw4j-agent/pom.xml` 去掉 flexmark 的 exclusion（`SmartWebFetchTool` 要 `FlexmarkHtmlConverter`，
  排掉是 `NoClassDefFoundError`）；`claw4j-capability/pom.xml` 补 `spring-web`
  （agent-utils 把它声明成 provided，`BraveWebSearchTool` 的 `RestClient` 要它，
  少这一条只在配了搜索 key 的部署里炸）。
- **端到端（`--spring.profiles.active=local`，仓库根起）**：Glob 找到 `examples/**/*.md` 九份；
  Read 读出 `examples/README.md` 前 6 行；TodoWrite 与 Skill 各调通一次（指标各 1）；
  WebFetch 抓 `https://example.com` 成功；不开写开关时模型明确说「没有 Write 工具」；
  `CLAW4J_FILES_WRITE_ENABLED=true` 起一次，`Write` 写出 `target/write-probe.txt`，内容对。
- **变异验证**：只读那支不过滤 → 「默认只有读」「Write 只在开时才在」两条红；
  去掉 `allowedDirectory` → 只有「目录外被拒」红；`SearchKeyPresent` 恒真 → 六条**全红**
  （没 key 时 `builder("")` 断言炸在装配期，正是这一条要防的）。
- **顺带修掉一个既有缺陷（同一根因，不修则本票功能不成立）**：`Path.of(".").toAbsolutePath()`
  在 Servlet 应用里不是启动目录——内嵌 Tomcat 起来时把 `user.dir` 改成了
  `/var/folders/…/T/tomcat-docbase.8080.…`。现场证据：`Bash` 的 `pwd` 打的是那个临时目录
  （`exec.workspace` 默认 `.`，早就这样），`Read` 说仓库里的文件"在允许目录之外"。
  修法是新增 `LaunchDirectoryEnvironmentPostProcessor`（`EnvironmentPostProcessor`，比 Tomcat 早），
  把启动时的 `user.dir` 记成 `claw4j.launch-directory`，`exec.workspace` 与 `files.directory`
  的默认值都改成 `${…:${claw4j.launch-directory:.}}`。修完两条实测都对。
- 门禁：`scripts/verify.sh` 全绿（失败 0、错误 0、跳过 0）。`claw4j-capability` 测试文件 11 个，
  超过"10 个以内"的软线、在"最多 20"的硬线内——新加的是独立的一层，没有再往别的文件里塞。

