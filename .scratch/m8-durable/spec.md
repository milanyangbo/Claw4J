# M8：能用得久（平台线 P08–P16）

Status: ready-for-agent
Date: 2026-11-03
裁决依据: ADR-0020（平台线 P08–P19，本目录覆盖其中的 M8）、ADR-0021（明确不做）、
ADR-0023（审计口径）、ADR-0019（agent 线的 M8 是 A06–A10，A06 是唯一的硬闸门）

## Problem Statement

M7 把"一件事能做完、企业在用"补齐了：run / step 是第一等身份，能批、能定时、能在企微收发、
出了事能查。但它撑不住长期运行，八处各缺一块：

- **凭据没有主**。模型 key 走 `${DEEPSEEK_API_KEY:}` 注入，MCP 的 `env` 是
  `Map<String, String>`（yml 里可以直接写真值），`headers` 的 javadoc 规定
  「凭据写成 `${ENV_VAR}` 占位符，值不进仓库」（`McpServerProperties:99`）——但占位符由 Spring
  在配置绑定时解析，真值在进程里以字符串形态活着，任何能读到配置的组件都拿得到，
  也答不出"这次调用用的是哪把钥匙"。脚本那条是对的（`SandboxPolicy.environment()` 只给 `PATH`）。
- **权限只有数据轴**（表 × 动作），表达不了"这个部署只做消息、不许跑 shell"：不给 `run_script`
  会把"读消息"一起卡住。
- **没有自己的知识库**。语义缓存那张表（`vector(1024)`）存的是问答对，命中直接返回历史回答，
  文档块不能进同一张表。
- **能力只能进不能出**：能接别人的 MCP server，不能把自己暴露成 MCP server。
- **通道只有 HTTP / 飞书 / 企微**，钉钉与 Slack 缺——而这两家是企业落地的第一道门。
- **观测只有计数器**（`claw4j.tool.calls{tool,outcome}`），一条 run 连不到它的 step 与每次工具调用。
- **只有一份 agent 配置**：角色、部门口径、技能、工具策略不能按 agent 组合。
- **插件没有版本契约**：插件依赖旧版 `claw4j-core` 而核心接口一变，是运行期
  `NoSuchMethodError`，不是启动时一句"这个插件要求核心 ≥ x.y"。

## Solution

八件事都挂在 M7 已经落地的接缝上，不新开旁路（ADR-0020 的唯一硬约束）：

1. **P08 凭据句柄**：配置里只写句柄名，出口前才替换成真值；模型在任何路径上看到的都是句柄。
2. **P09 审批策略升级**：在数据轴之外补一条**能力轴**——`minimal / messaging / coding / full`
   四档 profile + 按工具的 allow / deny 分组。
3. **P10 知识库检索**：向量 + 关键词混合，结果带出处；表与阈值与语义缓存分开。
4. **P11 反向 MCP server**：把我们自己的工具暴露成 MCP，让 Claude Code / Codex 当工具用。
5. **P12 / P13 钉钉与 Slack**：复用 P06 的通道模板，扩展点已经就绪。
   **P13 已定：入站走 Events API（HTTP），不走 Socket Mode。** 不是做不了（JDK 自带 WebSocket），
   而是官方建议生产用 HTTP、上架 Marketplace 必须用 HTTP，且 Socket Mode 是"我们连出去推"，
   与 `InboundDispatcher` 那套回调编排处处对不上。重估条件：完全不能开公网的内网。
6. **P14 可观测与 OTel 导出**：一条 run 能连到它的每个 step 与每次工具调用。
7. **P15 多 agent 配置**：角色 / 部门口径 / 技能 / 时间上下文 / 工具策略可组合、per-agent 可覆盖。
8. **P16 插件版本契约**：插件声明它依赖的 core 版本区间（不做市场）。

## User Stories

1. 作为运维，配置里只有句柄名，我在 yml、日志、审计、模型上下文里都搜不到真 key；
   出问题时要能答出"这次调用用的是哪个句柄"。
2. 作为部署者，我把工具策略切到 `messaging` 档，`run_script` 与 `draft_script` 不在工具清单里，
   读消息与查表都不受影响。
3. 作为业务使用者，我问"华东的退货政策怎么规定的"，回答带着出处（文档名 + 段落），
   而不是把某段文档当成"有人问过"的答案吐出来。
4. 作为开发者，我把 claw4j 配成一个 MCP server，Claude Code 能像用别的工具一样调我的业务工具。
5. 作为钉钉 / Slack 用户，我在自己的工作软件里 @ 应用就能用，三秒内收到确认，答案随后推回。
6. 作为运维，一条 run 在 trace 里能展开到它的每个 step 与每次工具调用，不用到库里拼时间戳。
7. 作为部署者，同一份代码两次部署只改配置，一个变销售助手、一个变运维助手。
8. 作为插件作者，核心版本不够时我在启动阶段就被告知"这个插件要求核心 ≥ x.y"，
   而不是等到某个接口被调用才 `NoSuchMethodError`。

## Implementation Decisions

- **句柄的替换点是出口，不是配置解析**：这是 P08 唯一真正的分歧点。现在 `${ENV}` 由 Spring 在
  配置绑定时解析，真值进入 `ModelProperties.Provider#apiKey` 这类 String 字段并活到进程结束；
  改成句柄之后，配置类只持有名字，真值在"发请求 / 起子进程 / 写 header"那一刻才解析。
  没有这一步，"句柄"只是给现有机制换个说法——照旧任何组件都能随手拿到真值，
  也照旧答不出"谁用了哪把钥匙"。
- **能力轴与数据轴正交，不合并**（P09）：`tools.profile` 决定"这个部署有哪些工具"，
  数据轴决定"这个人能碰哪些表、哪些动作"。合并成一个维度会让"换档位"变成一次权限重排，
  而两件事的变更节奏完全不同（一个是部署决定，一个是人事决定）。
- **知识库必须独立的表**（P10）：不复用语义缓存那张表——命中直接返回历史回答，
  文档块进同一张表的最坏情况是把一段文档当成"有人问过"的答案。共享 embedding 供应商与
  pgvector 实例即可，表与阈值必须分开，否则两边参数互相拉扯。
- **P10 的中文分词是前置条件，不是实现细节**：Postgres 默认分词器对中文失效
  （`to_tsvector('simple', 中文)` 切不出词）。开工前要先定 pg_bigm、应用侧抽词、还是外部检索，
  否则"混合检索"名不副实。这条写进票里，不许在实现中途发现。
  **已定：应用侧切 bigram。** 镜像里没有 pg_bigm（要自建镜像）、pg_trgm 的 `similarity`
  对长文档只有 0.083 因而做不了 rank、引中文分词库会丢精确匹配——三条的实测都在票面第一节。
- **P11 最后没有引 server starter**（实现后的实际状态，`f3dba26`）：本地仓库里
  `spring-ai-starter-mcp-server:2.0.2` 一直在，但它的 `ToolCallbackConverterAutoConfiguration`
  会把容器里四个 `ToolCallbackProvider` 原样暴露，绕过 P09 的档位、`MeteredToolCallback` 与
  鉴权注册表；而 `spring.ai.mcp.server.stdio` 默认 false，引了依赖也不等于能用。所以改成自己
  装配 `McpSyncServer`（`mcp-core` 已随 client starter 传递进来）。真要 webmvc 变体还得注意
  本地只有 1.1.5 / 1.1.6，离线构建会失败。
- **P14 的三件事不许合并**：审计是"谁让谁做了什么"（合规、落库可查，P07 已做），
  评测是"这个回答好不好"（质量、跑真值集，A06），观测是"现在系统怎么样"（Ops、可聚合）。
- **P15 合成一张票**：tools profile 与提示词分层分开会长出两套"人设"配置，它们是同一件事。
- **P16 只做契约，不做市场**：插件声明依赖的 core 版本区间，启动时校验；不做分发、不做索引。
- **模块方向不变**：`channel → core ← agent`（ADR-0010）；一切模型调用从同一条 advisor 链进（ADR-0007）。

## Testing Decisions

- **每条判据都要可判定，且能用一句话写出反例**。"模型上下文里搜不到真 key"不是靠人眼看的：
  让工具回显入参与环境、让 MCP 的 `echo` 回显参数，断言输出里没有真值子串；
  审计那一侧断言 `args_summary` 与 `args_digest` 里没有真值。
- **沿用 M7 的四条缝**：HTTP 缝（进）、指标缝（`claw4j.*` 回读）、SQL 缝（迁移用 SQL 用例钉
  表结构与唯一键，含变异）、真模型缝（闭环用例连真模型跑，mock 只证明装配）。
- **P09 的判据是"清单里没有"，不是"调了被拒"**：切到 `messaging` 档之后 `run_script`
  根本不在工具清单里——"调了才发现被拒"会让模型先计划再撞墙，白烧一轮上下文。
- **P10 要有"不是缓存"的用例**：同一段文档被两次检索要都带出处；把文档块混进语义缓存表
  那种实现，必须有一条用例变红。
- **门禁沿用本地 `./scripts/verify.sh`**（checkstyle / spotbugs / 结构规则 / 覆盖率下限），
  不引 CI——这是既定决策，不是遗漏。

## Out of Scope

- **A06–A10（ADR-0019 的另一条 M8「答得对」）不在本目录**：本目录只覆盖平台线。
  但要知道它在那儿——ADR-0019 第 197 行原文：「**A06 是 M8 唯一的硬闸门**。它之前的所有
  '改好了'都是自述，之后才有比较。这条决定了 A07–A10 的顺序不可调换，也决定了 M8 的第一件事
  写不出漂亮的 demo」。所以"先做 P08"是产品取舍，不是 ADR 给出的顺序。
- M9 的 P17–P19（脚本沙箱加固、`doctor` 自检、WebSocket 传输）。
- 插件市场与分发（P16 只做契约）。
- 审批流引擎（多级会签、转交、超时升级）——ADR-0021 已否决。
- `heartbeat` 常驻自唤醒——A04 与 P07 都已落地，重估条件成立但重估结论是"它只是 `schedule_task`
  的上层用法"，归下一张票，不在这批。

## Further Notes

- **票按需逐张拆**，一个票一个文件，落在 `issues/P<NN>-*.md`，编号取自 ADR-0020 的原文
  （P08–P16，含 P12 / P13），不另起编号、不补空号。
- **依赖边**（按 ADR-0020 的描述推出来的真实依赖，不是优先级）：
  - P08、P09、P12、P13 互不依赖，可并行；
  - P10 的分词方案**已定**（应用侧切 bigram，实测数据在票面第一节），不再是阻塞项；
  - P14 依赖 P07 的审计口径（三条时间线不许混）；**已定：根 span 是 run，step 是 span 的属性**
  （`claw4j.step.seq`），不做请求级 traceparent 透传；SDK 直连 OTel 1.35.0、走 HTTP 不走 gRPC，
  两条都是离线仓库实测出来的，见票面第一节；
  - P15 依赖 P09 的 profile（工具策略是它的一层）。
- **实现中发现与本目录或 ADR 不一致时，先回来改文档再写代码**——它们是已接受的裁决。
- 每一张票收票时把"验收框逐条勾 + 证据落到方法名/文件名"，不要只写 Comments：
  M7 的 A01 / A02 就是只写了 Comments、验收框一条没勾，事后补了一轮。

## Comments

- 2026-11-03 建目录与 spec。范围按 ADR-0020 的 M8 段（P08–P16，九张），
  **只拆了 P08 一张**：其余八张的验收判据 ADR 原文每张只有三五行，一次扩完等于由我编细节，
  按需逐张拆更靠得住。要一次拆完说一句，我按同一套形式补。
- 写 spec 时核过的现状（避免文档比代码走得远）。**P08 之前的快照**：模型 key 与 MCP 的
  `env` / `headers` 三项已被 P08 改成句柄，下面只剩脚本那一条仍准。原文如下——
  模型 key 是 `application.yml` 的
  `${DEEPSEEK_API_KEY:}` 占位符，配空则启动失败（`RealChatModelCondition`）；
  MCP 的 `env` 是 `Map<String, String>`（允许直写真值），`headers` 的 javadoc 要求写
  `${ENV_VAR}` 占位符（`McpServerProperties:99`）——但两者都在配置绑定时就解析成真值；
  脚本侧 `SandboxPolicy.environment()` 只返回 `PATH`，这一条已经是对的。
- 2026-11-03 拆票进度：P08（已实现，`4234aab`）、P09（已实现，`5913a77`）、
  P15（已实现，`630c327`）、P11（已实现，`f3dba26`）、P12（已实现，`9389e07`）、
  P10（已实现，`58fc525`）、P13（已实现，`74237fe`）、P14（已实现，未提交）。
  P15 排在 P09 之后，因为它依赖 profile 这一层；写 P15 票时核到的三条硬事实都落到了具体代码位置：
  人设是 `AgentAutoConfiguration:64` 的 Java 常量、口径被抄在 Skill 与 Subagent 的正文里、
  时间上下文完全不存在（`weekly-sales` 的相对时间靠 SQL 的 `current_date` 兜）。
  写 P11 票时查到两件改变实现形态的事，都写进了票面：`spring.ai.mcp.server.stdio` 默认 false
  （只引依赖得到的是"stdio 没开、也没有别的传输"，不是"什么都没发生"）；server 侧的
  `ToolCallbackConverterAutoConfiguration` 会把容器里四个 `ToolCallbackProvider` 原样暴露，
  绕过 P09 的档位、包装与鉴权。所以这张票不引 server starter，自己装配 `McpSyncServer`。
- 2026-11-03 拆 P12。ADR 把两张写在一句里（「P12 / P13 · 钉钉 / Slack 通道」），
  **按书写顺序取 P12 = 钉钉**，这是本票唯一的推断，其余都核过。
  写票时核到的三件改变实现形态的事：
  1. 钉钉有三条都叫"接收消息"的路（机器人 HTTP 回调 / 企业事件订阅 / Stream），
     只有第一条的载荷里有『谁说了什么』。第二条虽是加密 JSON、能照抄企微那份加解密，
     但推的是通讯录与群事件——接完会发现收不到业务消息。
  2. 机器人回调是**明文 JSON + header 签名**（`HmacSHA256(appSecret, timestamp + "\n" + appSecret)`），
     与企微的加密 XML 不是一类；`senderStaffId` 在机器人未发布上线时**不返回**。
  3. `createAt` 是毫秒、企微的 `CreateTime` 是秒（P06 记过照抄会得到 1970 年，这次方向相反）；
     钉钉换 token 有效期内返回同一张，企微那段"平台先一步废掉"的重试逻辑不能照抄。
- **channel 模块的测试文件已经满了**（10 个）。P12 若不能新增文件，只能合并腾槽
  （`FeishuOutboundDeliveryTest` 1 条并进 `FeishuChannelWiringTest`）或塞进未满的文件。
  这条约束在 P12 票里写成了三条出路，开工前先定。
  实现时选了第一条：`FeishuOutboundDeliveryTest` 并进 `FeishuChannelWiringTest`（9 → 10）后删掉，
  槽位给 `DingtalkChannelTest`（18 条）。那条用例的断言原样搬过去，不是重写。
- P12 实现后 app 那一侧又顶到了上限：`ApplicationAssemblyTest` 从 17 条加到 18 条时，
  连同三个探针配置类一共 21 个（脚本按 `@Test` 子串数，`@TestConfiguration` 也算），
  于是把企业微信与钉钉的两个记录器合进一个 `ChannelProbes`（两个列表仍然分开）。
- 2026-11-03 拆 P10，卡点已解。四条路都在本机 `pgvector/pgvector:pg16` 上实测过
  （临时容器，跑完已删），不是查文档得来的：
  1. 默认分词器确认失效——`to_tsvector('simple', '华东区的退货政策怎么规定的')` 得到
     `'华东区的退货政策怎么规定的':1`，整句一个 token。
  2. **镜像里没有 pg_bigm / zhparser / pgroonga**（`pg_available_extensions` 只有 vector 0.8.7
     与 pg_trgm 1.6）。用它就得自建镜像，而这个项目的镜像已经被网络卡过一次。
  3. `pg_trgm` 做不了 rank：`similarity('退货政策', 一段长文档)` = 0.0833，分母随文档变长而变大，
     阈值没法跨长度统一；`word_similarity` = 0.4 但无词频加权。
  4. 应用侧 bigram 交给 `simple` 分词器可行：12 个 token、多词 AND 命中、不相关词不命中、
     单字用 `退:*` 前缀命中、`ts_rank` = 0.268330；生成列 `to_tsvector('simple', bigrams)`
     与 GIN 索引都建得出来（生成列要求 immutable，这一条不验就可能在实现时才发现建不了表）。
  另两个结构问题也定了：**embedding 模型从 cache 的装配里提出来**（现在"关掉缓存"会连带
  关掉知识库要用的向量模型），**检索做成工具而不是 advisor 自动注入**（鉴权、审计、分级
  三件事都落在工具这一条路上）。
  顺带纠一处自己写错的：先前打算写"空 tsquery 是全部命中"，实测 `@@` 为 `false`
  ——退化成"一条都不命中"，比响亮地报错更难查。
- 2026-11-03 P10 实现。三件事记在这里，因为它们都影响下几张票的写法：
  1. **rag 的 Java 层没有覆盖率**。真容器那边验的是 SQL（生成列、两路 topK、出处回查），
     不是 `KnowledgeIndexer` / `PgVectorKnowledgeBase` / `KnowledgeTools`——三个类共 140 行，
     agent 模块的行覆盖率因此从 0.62 掉到 0.58，闸直接红。**"SQL 真跑了"不等于"类被覆盖了"**，
     以后每加一个碰库的类都要按这个算一遍。
  2. **腾测试文件槽不能只按票面写的数算**。票面计划"塞进 `SemanticCacheTest`"，
     但那个文件已经 20 条，塞不下三个类的用例。实际做法是把 `audit/ToolCallAuditTest`(13)
     与 `session/RecallSessionTest`(8) 合成 `session/ToolGovernanceTest`（合并一条同 setup 的
     批准用例后 20 条）——它们问的是同一件事的两端：工具进来时能不能用、出去后留下什么。
     合并后的文件放在 `session` 包里是不得已：`RecallSessionTool.MAX_RESULT_CHARS` 是包内可见的。
  3. **`simple` 分词器 + 应用侧 bigram 这条路在真库上闭合了**：`bigrams` 列 + 生成列 `tsv`
     + GIN 索引，两路各取一次 topK，中文关键词实测命中。票面第一节那 12 个 token 与
     `KnowledgeTextTest` 的断言逐字一致——实测抄进用例，不是照着实现回填的。
- 2026-11-03 拆 P13（Slack）。写票时核到四件改变实现形态的事，都不在 ADR 那一句里：
  1. **Socket Mode 这次不是"做不了"而是"不该做"**。P12 的 Stream 模式卡在本地仓库没有 SDK，
     这条不成立——JDK 自带 `java.net.http.WebSocket`，硬写能写。真正的判据是官方的部署建议
     （开发/本地用 Socket Mode，**生产用 HTTP**，上架 Marketplace 必须 HTTP），加上"它是我们连出去推"，
     `InboundDispatcher` 那套一处也用不上，等于为一家单开旁路。**如实记下重估条件**：
     完全不能开公网的内网里，Socket Mode 是唯一出路。
  2. **自己发出去的话会被推回来**。前四个通道都没有这个问题，Slack 有——不滤 `bot_id` /
     `subtype`，一次提问会变成一问一答的无限循环，不报错、只在账单上体现。
     而且只滤 `bot_id` 不够：`message_changed` / `channel_join` 这些不是 bot 发的，
     `text` 要么在 `event.message.text` 里（多嵌一层）、要么是"某某加入频道"这种会真的进模型的句子。
  3. **`ReplySender.send(conversationId, text)` 装不下"频道 + 线程"**。核实过 `conversationId`
     在 Agent 侧零消费点（`grep` 过 `claw4j-agent/src/main/java`），所以编码成 `channel|thread_ts`
     是安全的，代价是主动投递只能推回那个具体线程（要发私聊得先 `conversations.open`，不做）。
  4. **两处"看着对、实则反"的协议细节**：订阅项叫 `message.im` 而载荷里 `event.type` 是 `message`
     （按订阅名判断 → 单聊永远匹配不到，且不报错）；`chat.postMessage` 的失败是 HTTP 200 +
     `{"ok":false}`（照抄钉钉/企微那套"200 就当成功"的写法，会得到一个永远成功的发送器）。
  另外把两处门禁余量也写清了：`claw4j-channel` 与 `claw4j-app` 都已 10 个测试文件，
  app 那个 `ApplicationAssemblyTest` 还已经 20 条顶格——**P13 想加闭环用例，必须先腾两个槽**。
- 2026-11-03 P13 实现。四件事记在这里，因为下两张票（P14、P16）会碰到同样的形状：
  1. **"不做"的理由要写清是哪一种**。P12 拒 Stream 模式是"做不了"（本地仓库没 SDK）；
     P13 拒 Socket Mode 是"不该做"——JDK 自带 WebSocket，写法上做得出来，但官方建议生产用 HTTP、
     上架必须 HTTP，而且它是"我们连出去推"，`InboundDispatcher` 那套一处也用不上。
     两张票的理由不同，票面里各写各的，别把它们都记成"没有 SDK"。
  2. **第一个会收到自己消息的通道**。Slack 把应用发出去的消息再推回来（`bot_id`），
     被编辑的消息也推（`subtype`）。不先挡就是无限循环，而且不报错、只在账单上体现。
     以后接任何"应用以自己身份发言"的平台（Discord、Teams），第一件事都是问"它会不会把我的话推回来"。
  3. **门禁腾槽这次两处都是"同主题合并"，且第二次合并比第一次便宜**。
     `ApprovalControllerTest` + `RunQueryControllerTest` → `HttpEndpointsTest`（两条同口径的
     401 用例顺手并成一条，净省一条）；`ProcessBoundaryTest` + `MigrationCompositionTest` →
     `BuildContractTest`（两份守的都是"不会在运行时报错、只在某天以另一种形态暴露"的约定）。
     代价都是 diff 碰到别人的测试文件，断言原样搬运。
  4. **协议细节值得写成"数"而不是"曾经"**：3 秒应答、60 分钟 95% 失败率会停订阅、同频道 1 秒 1 条、
     签名窗口 5 分钟、时间戳是秒、`text` 建议 ≤4000（与 `MessageSplitter` 的默认值正好一致）。
     这些数写进票面与 README 之后，下一个人不必再查一遍文档，也不必猜我们抄的是哪一家。
- 2026-11-03/04 拆 P14（可观测与 OTel 导出）。四件事决定实现形态，其中前两件是**离线仓库实测**得来的，
  不是查文档：
  1. **Boot 4.1.2 的 OTel 支持在离线环境里不可用**。它管着 `opentelemetry.version=1.62.0`、
     `micrometer-tracing.version=1.7.2`，也管 `spring-boot-starter-opentelemetry` 三个坐标，
     但本地仓库一个 jar 都没有（`micrometer-tracing` 只有 1.5.6）。而 `opentelemetry-sdk:1.35.0` +
     `opentelemetry-exporter-otlp:1.35.0` 的整条链离线解析成功（临时 pom 实测，依赖树在票里）。
     所以直连 SDK、显式版本，与 P11 不用 mcp-server starter 同一条理由。
  2. **只能走 HTTP，不能走 gRPC**。jar 里两个 exporter 都有，但运行时依赖只带
     `exporter-sender-okhttp`——`OtlpGrpcSpanExporter` 编译得过、启动时找不到 sender。
     这是「本地跑得起来、换台机器就炸」的那类错，写进票面。
  3. **根 span 是 run 不是请求**。五个通道都把 `agent.reply` 丢进 `ThreadPoolTaskExecutor`，
     请求的 trace 在入池那一刻就断；而 run 跨通道、跨重试、跨 resume。子 agent 那边也核过：
     `SubagentRunner` 没有 `AgentPort` / `RunStore`，所以它不会长出第二棵树。
  4. **step 不做 span，做 span 的属性**。给一行 step 开 span，duration 只是一次 INSERT；
     而 `APPROVAL` 会挂几小时。改成「动作 span 关闭前写 `claw4j.step.seq`」，代价是
     `ToolCallAudit#executed` 要返回 `Optional<Step>`（现在 void）——那也正是审计行与 span
     唯一能对上号的地方。
  另外把两条容易被忽略的机制写进了判据：采样必须 `ParentBased(TraceIdRatioBased)`（裸 ratio 会
  出现「有 run 无 tool」），provider 关闭前必须 flush（丢的恰好是出事前那几秒）。
- 2026-11-04 P14 实现。四件事记在这里，前三件会再出现：
  1. **dependencyManagement 是"先声明的 BOM 赢"，不是后声明的**。把 `opentelemetry-bom`
     放在 Boot BOM 之后时版本纹丝不动（仍 1.62.0），移到前面立刻全链 1.35.0。
     而且**只给直接依赖写 `<version>` 不够**：传递依赖仍按 BOM 解析，报错信息只会说
     "1.62.0 没下载过"，看起来像网络问题。判据固定成"改完 BOM 必须 `dependency:tree` 看版本"，
     已写进 `docs/design/pitfalls.md` 第十六条。
  2. **观测与审计的分界落在"返回 seq"这一件事上**。`ToolCallAudit#executed` 从 `void` 改成
     返回 `Optional<Step>`，span 上写 `claw4j.step.seq`——`agent_step` 表一行没改，
     也没加 `trace_id` 列。以后谁想问"trace 能不能直接查库"，答案是"按 seq 反查，别再存一份"。
  3. **端口这一层看不到的东西不要编**。票面写 `gen_ai.route`，但路由名在 `MeteredChatModel` 里，
     `ChatClientAgentPort` 拿不到；于是改用响应里的 `gen_ai.response.model`，并在实现记录里
     写明替换理由。同理 `claw4j.owner` 只放权限指纹——trace 会出内网。
  4. **门禁的数字要重数一遍再写进票**。P13/P14 两次都把 `@Testcontainers` / `@BeforeAll`
     算成了用例（脚本按 `@Test` 子串数），于是"6 + 6 = 12"实际是 10 条。
     文件数那条（10 个）没受影响，但"合并省下几条"这种描述会失真。
