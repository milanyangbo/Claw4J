# P16 · 插件版本契约：核心版本不够时，启动那一秒就告诉你

**Status:** done（2026-11-04 实现，未提交）
**Blocked by:** 无。它与 M8 其余七张没有交集，也不依赖 P09 / P15。

## 背景

ADR-0020 的原话：

> OpenClaw 有 ClawHub 与版本兼容校验；我们插件机制是 Boot 自动配置，既无分发也无版本口径。
> 这是 README「还没做的」里的第一条真限制。要做的是**契约**（plugin 声明它依赖的 core 版本区间），
> **不做市场**。

README「还没做的」第一条现在写的是：

> **插件与核心的版本兼容口径没定义**：没有任何版本校验，插件依赖旧版 `claw4j-core` 而核心接口一变，
> 就是运行期 `NoSuchMethodError`，而不是启动时一句"这个插件要求核心 ≥ x.y"。现在只能靠"同时升级"。

这句话要能被删掉，就是这张票的验收标准。

### 现状（实测，不是回忆）

**插件是怎么被发现的**：一个 jar + `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
一行全限定类名（`docs/design/extension-points.md` 开头那句）。发现机制 Spring 已经给了，不需要我们发明，
这张票也不发明。

**问题在于这套机制里没有任何"谁跟谁配"的信息**。两处证据：

1. `claw4j-core` 的 jar 清单里**没有** `Implementation-Version`（实测
   `unzip -p claw4j-core/target/claw4j-core-0.1.0-SNAPSHOT.jar META-INF/MANIFEST.MF`）：

   ```
   Manifest-Version: 1.0
   Created-By: Maven JAR Plugin 3.5.0
   Java-Version: 21
   Build-Jdk-Spec: 21
   ```

   于是 `SomeCoreClass.class.getPackage().getImplementationVersion()` 恒为 `null`。

2. `META-INF/maven/io.github.claw4j/claw4j-core/pom.properties` **有** `version=0.1.0-SNAPSHOT`，
   但它由 jar 插件生成，**只存在于 jar 里**：`target/classes/META-INF/` 下没有这一份。
   也就是说「`mvn spring-boot:run` 起得来、打成 jar 也起得来」这句话不成立——按它读版本，
   开发态永远读不到，而读不到的那条路会变成"跳过校验"，是这张票最不想留下的形状。

**失败形态**：插件编译时对着旧的 `claw4j-core`，核心删了一个方法重发版，插件照常被发现、
照常注册 bean，直到模型真调到那个工具、或者某个 bean 初始化走到那行走一次——
`java.lang.NoSuchMethodError: 'void io.github.claw4j.domain.port.X.y()'`。
日志里没有任何一行提到"版本"，栈里全是插件自己的类名。

## 必须先回答的五个结构问题

### 1. 核心版本从哪儿读：一份被 Maven 过滤过的 properties

三个候选，前两个都实测过，都缺一角：

| 来源 | 在 jar 里 | 在 `target/classes` 里 | 结论 |
| --- | --- | --- | --- |
| `Package#getImplementationVersion()` | 没有（清单里无该字段，见上） | 没有 | 要用就得给所有模块配 `maven-jar-plugin` 的 `addDefaultImplementationEntries`，且开发态仍读不到 |
| `META-INF/maven/<g>/<a>/pom.properties` | 有，免费 | **没有** | 开发态读不到 → 只能"未声明即跳过"，等于没有闸 |
| `META-INF/claw4j-core.properties`（过滤后） | 有 | **有** | 选它 |

实测（临时给 `claw4j-core/pom.xml` 加 `<resources>` 后跑 `./mvnw -o -pl claw4j-core process-resources`，
验完已还原）：源文件里写 `version=${project.version}`，`target/classes/META-INF/claw4j-core.properties`
里是

```
# 探测用
version=0.1.0-SNAPSHOT
```

中文注释也原样过关（`project.build.sourceEncoding` 是 UTF-8，这一条没被过滤打坏）。

**过滤必须按 `includes` 收窄到一个文件**，不能让整个 `src/main/resources` 一起过滤：这个仓库的
`resources` 里躺着 Flyway 的 `db/migration/V*.sql`，`${` 一旦出现在 SQL 里就会被替换成一个
空串或者报错——而那是一次"看起来迁移过了"的静默损坏。写法是两条 `<resource>`：
一条 `filtering=true` + `includes`，一条 `filtering=false` + 同一个 `excludes`。

### 2. 契约写在插件的哪个文件里：`META-INF/claw4j-plugin.properties`

```properties
name=sales
requires-core=>=0.1.0 <0.2.0
```

两件实测：

- **`Properties` 能原样读出这个区间**。`requires-core=>=0.1.0 <0.2.0` 用默认分隔符解析出来是
  `[>=0.1.0 <0.2.0]`（第一个 `=` 是分隔符，值里的 `>` `<` 不动，也不需要转义）。
- **`classpath*:` 扫得到 jar 里的这一份**。用一个临时 jar（只有一个
  `META-INF/claw4j-plugin.properties`）挂到 `URLClassLoader` 上，`PathMatchingResourcePatternResolver`
  拿到的是：

  ```
  URI = jar:file:/tmp/probe/probe-plugin.jar!/META-INF/claw4j-plugin.properties
  ```

  这条很要紧：探测的写法是 `PathMatchingResourcePatternResolver(new URLClassLoader(...))`，
  也就是**测试里能用一个真 jar 复现"插件在另一个 jar 里"这件事**，不必等到部署才知道。

为什么不用清单（`Claw4j-Requires-Core: ...`）：清单要靠每个插件模块配 `maven-jar-plugin` 的
`manifestEntries`，而 `target/classes` 里根本没有清单——同一个"开发态读不到"的洞。
`imports` 文件那条路也不行：它是 Spring 的格式，写进去的东西 Spring 不认识，等于把契约塞进别人的命名空间。

### 3. 什么时候校验：`BeanFactoryPostProcessor`，不是普通 bean

要求是"在插件的 bean 被创建之前"报出来。普通 bean 做不到——谁能先创建由依赖关系决定，
插件的 `@Bean` 方法体里第一次碰到已删除的方法时就会先炸，我们那条"版本不对"的消息永远轮不到。

`BeanFactoryPostProcessor` 在 `invokeBeanFactoryPostProcessors` 阶段跑，早于任何单例 bean 的实例化，
也早于插件的 `@Bean` 方法被调用。写成自动配置里的 `@Bean static` 方法即可；失败时 Spring 抛的
`BeanCreationException` / `BeanDefinitionStoreException` 会把我们那句话原样带出来。

代价写在明处：BFPP 里**不能创建 bean**，所以它只能读资源、读 `Environment`，不能注入任何东西。
这条限制正好逼着"校验"与"装配"分开，不是坏事。

### 4. 怎么比较：只比数字三段，限定符不参与

版本形如 `major.minor.patch[-qualifier]`，缺的段按 `0` 补（`0.1` 等于 `0.1.0`）。
比较只看三段数字，**限定符一律忽略**——`0.1.0-SNAPSHOT` 与 `0.1.0` 等价。

这一条是刻意选的，理由是这个仓库里唯一的限定符就是 `SNAPSHOT`：

- 按 Maven 的排序，`0.1.0-SNAPSHOT < 0.1.0`。照那个语义，同一个 reactor 里构建的示例插件
  声明 `>=0.1.0` 会当场校验不过——而它明明就是这一版核心编出来的。
- 这套契约要回答的问题是"接口在不在"，不是"这是不是正式版"。把预发布也当成一次接口变更，
  只会教人写 `>=0.1.0-SNAPSHOT`，那个区间在正式发布核心之后立刻就不匹配了。

于是 `0.2.0-SNAPSHOT` 也按 `0.2.0` 比，会被 `>=0.1.0 <0.2.0` 拒掉——这正是要的方向：
破坏性版本的预发布版不该被旧插件当成兼容。

区间语法，全部支持的就这些：

| 写法 | 含义 |
| --- | --- |
| `>=0.1.0 <0.2.0` | 两个比较符用空格或逗号连，同时成立（AND） |
| `>=0.1.0` | 只有一个下界 |
| `<0.2.0` | 只有一个上界 |
| `0.1.4` | 裸版本＝精确匹配 |
| `*` | 任意版本 |

不支持 `^0.1.0`、`~0.1`、`||`、Maven 的 `[1.0,2.0)`。理由不是懒：两套语法并存时，
"这个区间到底什么意思"要靠人记；而 `[0.1.0,0.2.0)` 与 `>=0.1.0 <0.2.0` 在 0.x 上等价，
多看一行就能读懂的写法更值得留着。**畸形区间一律判不通过**，不是判通过——
`=>0.1.0` 这种笔误如果被当成"没条件"，这道闸就白装了。

### 5. 校验谁：只有声明了的插件；没声明的不校验

能枚举的只有"有描述文件的 jar"。**没有描述文件的插件与宿主自己的模块长得一模一样**
（`AutoConfiguration.imports` 里一行类名，没有任何标记），所以"它没声明"这件事在运行期
区分不出来。两个出路：

- 按包名白名单（`io.github.claw4j.*` 算宿主，其余算第三方）——第三方用了别的前缀就漏，
  而"漏"这件事不会报错。
- 不猜。

选不猜，并把这条写成已知局限。补一个**构建期**的兜：示例插件（`claw4j-example-sales`）自己带一条用例，
断言"我声明的区间接纳当前 classpath 上的核心版本"。核心一升 minor，这条当场红——那是仓库能控制的
那部分，第三方控不了的部分不假装能控。

## 落点

```
claw4j-core/src/main/resources/META-INF/
    claw4j-core.properties                   version=${project.version}（过滤）
claw4j-core/pom.xml                          <resources> 两条，只过滤这一个文件
claw4j-core/src/main/java/io/github/claw4j/plugin/
    CoreVersion.java                         三段数字，Comparable；parse 失败抛带原文的异常
    CoreVersionRange.java                    >=、>、<=、<、=、* 的组合；matches(CoreVersion)
    PluginContract.java                      Map<来源, Properties> → List<Violation>（不抛、不碰 IO）
claw4j-app/src/main/java/io/github/claw4j/app/
    PluginContractGate.java                  BFPP：扫 classpath → 读核心版本 → 判 → 抛
    PluginContractAutoConfiguration.java     装配（`@Bean static`，BFPP 必须是静态方法）
claw4j-app/src/main/resources/META-INF/spring/
    ...AutoConfiguration.imports             一行；app 此前没有这个文件
claw4j-example-sales/src/main/resources/META-INF/
    claw4j-plugin.properties                 name=sales；requires-core=>=0.1.0 <0.2.0
```

**判断在 core、装配在 app**，两条理由：

- core 的 enforcer 明令禁止 Spring（`claw4j-core/pom.xml` 的 `core-must-stay-framework-free`），
  所以它只放"版本是什么、区间怎么读、谁不匹配"这三件纯计算，一个 `java.util` 之外的类型都不用。
- 扫描 classpath 与让启动失败必须由 Spring 来说话，而那道闸是**启动期装配**的一部分——
  app 的职责原文就是"启动、配置与容器编排"（README 的模块表）。

票面原先写的是放 `claw4j-capability`（照 P08 的先例：纯契约在 core、Spring 适配在 capability）。
实现时改掉了，原因是硬约束：**capability 的测试文件名额满了**——它有 10 个 `.java`，
其中两个是 MCP 的测试替身（`InProcessStdioMcpServer`、`PipedStdioClientTransport`，都是 public 类，
一个文件只能放一个），剩下八个测试类两两不同包、各自用到包内可见的类型，**没有一处能合法合并**。
硬塞进去就是第 11 个文件，门禁直接红。

代价如实写：**自己另起一套 Spring 启动的用法人不会自动获得这道闸**，要自己调一次判断。
这条已写进"已知局限"。

配置键只有一个，而且**是零个**：这张票不加任何可配置项。

一开始设计过一个 `claw4j.plugin.enforcement=fail|warn`，`warn` 给灰度期用。砍掉了，理由是它要服务的
场景不存在：灰度或回滚时，核心与插件本来就在同一个版本组合上，版本匹配用不上 `warn`；
真正需要 `warn` 的是"核心升了、插件没升"，而那正是要拦住的那件事。
留下一个开关的后果是可预见的——出问题先被关掉，而不是被修。一个关不掉的闸才是闸。

失败消息要能自己说完一件事（插件名、它声明的区间、实际核心版本、描述文件来自哪个 jar），
并且**一次把所有不匹配的插件都列出来**——升级核心的人想知道"是不是只有这一个"，而不是修一个重启一次。

## 验收判据

- [x] **匹配就照常启动**：`requires-core=>=0.1.0 <0.2.0` + 核心 `0.1.0-SNAPSHOT` → 上下文正常起来，
      且不产生任何 WARN。反例：判定写成"必须严格相等" → 这条红。
      证据：`BuildContractTest#aMatchingDeclarationStartsQuietly`（目录形态）与
      `PluginContractTest#acceptsAReleaseVersionInsideTheRange`（纯判定）。方法是 `void`，
      唯一的失败形态是异常，所以"不抛"就是结论。
- [x] **不匹配就起不来，且消息说得清**：区间改成 `>=0.2.0` → 启动失败，消息里同时出现
      插件名、`>=0.2.0`、`0.1.0-SNAPSHOT`、描述文件的来源。反例：只抛 `IllegalStateException("版本不匹配")` → 红。
      证据：`BuildContractTest#aMismatchStopsStartupAndSaysEverything` 四条 `hasMessageContaining`
      各对一件事（`插件 sales` / `>=0.2.0` / `0.1.0-SNAPSHOT` / `sales.jar`）。
- [x] **一次报全部**：两个插件都不匹配时，一条消息里两个都在（不是第一个就抛）。
      反例：`for` 循环里 `throw` → 红。
      证据：`BuildContractTest#twoMismatchesAreReportedTogether`（两个真 jar）、
      `PluginContractTest#reportsEveryMismatchedPluginInOnePass`（判定层返回 2 条）。
- [x] **畸形区间判不通过**：`requires-core=>=0.1.0`（`=>` 是笔误）→ 启动失败并指出这个 token。
      反例：解析失败时 `return true` → 红，而这条正是"闸看起来装着其实没装"的唯一防线。
      证据：`BuildContractTest#aMalformedRangeStopsStartup`、
      `PluginContractTest#rejectsAMalformedTokenInsteadOfPassingIt`（消息里带原文与"读不成一个区间"）。
- [x] **缺 `requires-core` 判不通过**：文件在、键不在 → 启动失败，点名那个文件路径。
      反例：`Objects.requireNonNullElse(..., "*")` → 红（缺省成"任意"等于放行）。
      证据：`BuildContractTest#aDescriptorWithoutTheKeyStopsStartup`、
      `PluginContractTest#rejectsAMissingDeclaration`（键缺失与值为空白都算，两条断言）。
- [x] **预发布按同号正式版比**：核心 `0.1.0-SNAPSHOT` 能被 `>=0.1.0 <0.2.0` 接纳；
      反过来核心 `0.2.0-SNAPSHOT` 被同一个区间拒掉（两条断言在同一份用例里，方向相反）。
      证据：`PluginContractTest#treatsASnapshotAsItsRelease`（同一测试里两个方向，第二段还断言消息里
      出现 `0.2.0`，证明拒的是那个预发布而不是别的什么）。
- [x] **核心版本读不到就失败**：classpath 上没有 `META-INF/claw4j-core.properties` → 启动失败并说明缺的是哪一份。
      反例：读不到时当成"已知"跳过 → 红。
      证据：`BuildContractTest#aMissingCoreVersionStopsStartup`（消息里带
      `PluginContractGate.CORE_VERSION_RESOURCE` 这个常量，不是随手写的字符串）。
- [x] **jar 里的描述文件能被发现**：测试现场造一个只含 `META-INF/claw4j-plugin.properties` 的 jar，
      挂到 `URLClassLoader` 上，用真 `PathMatchingResourcePatternResolver` 扫到它并判出违规。
      反例：把扫描改成读 `target/classes` 目录 → 这条红（它证明的是"插件在另一个 jar 里"这件事）。
      证据：`BuildContractTest#aMismatchStopsStartupAndSaysEverything` 与
      `#twoMismatchesAreReportedTogether` 的输入都是 `JarOutputStream` 现造的 jar；
      目录形态另有 `#aMatchingDeclarationStartsQuietly`，两种形态各有一条。
- [x] **`warn` 那条没了**：本票不含任何开关，`grep enforcement` 在代码里搜不到东西。
      反例：有人"顺手"加回一个 `enabled` 默认 false → 红（这条判据的对象是"没有开关"这件事本身）。
      证据：`PluginContractGate` 的 javadoc 末段与 `PluginContractAutoConfiguration` 各写了一句
      "没有开关"，代码里没有任何 `Properties` 绑定类；契约判定只认 `requires-core`。
- [x] **没有描述文件不报错**：整条 classpath 一个描述文件都没有 → 照常启动（宿主自身的模块就是这样）。
      证据：`BuildContractTest#anEmptyClasspathBootsFine`（classpath 上只有核心版本文件）。
- [x] **示例插件的声明与当前核心自洽**：`claw4j-example-sales` 里一条用例，读自己的描述文件、
      读核心版本、断言区间成立。核心升 minor 而没人改描述文件时，构建就红——不等到部署。
      证据：`SalesPluginContractTest#theDeclaredRangeAdmitsTheCoreThisPluginWasBuiltAgainst`。
      它读的是 `META-INF/claw4j-core.properties` 与自己的 `META-INF/claw4j-plugin.properties`，
      走的是与启动期同一条判定（`PluginContract.checkAll`）。
- [x] **默认不改变现有行为**：`./scripts/verify.sh` 全绿，跳过 0，既有用例一条不改地通过。
      证据：2026-11-04 跑过——**646 条用例、失败 0、跳过 0**。既有用例只加不改：
      `BuildContractTest` 从 4 条加到 12 条（原 4 条断言一字未动），
      `AuthorizationTest` 是 `ApproverRosterTest`(4) + `AuthorizationDecisionTest`(11) 的原样搬家，
      其余 `ObservabilityTest`、`RunLifecycleTest` 等一条没碰。
- [x] `./scripts/verify.sh` 全绿，跳过 0，行覆盖率不低于 60%。
      证据：core 90.2% / channel 75.8% / capability 85.6% / agent 65.4% /
      example-sales 93.8% / example-mcp-server 75.0% / **app 80.8%**（此前是 n/a——app 第一次有
      被统计的主代码类，就是这道闸）；七个模块都是 10 个测试文件、单文件最大 20 条。

## 不做的事

1. **不做市场、分发、索引**（ADR-0021 已记）。这张票不碰"从哪装插件"，只碰"装进来的那个能不能用"。
2. **不发明 `Claw4jPlugin` 接口或 SPI**。发现机制 Spring 已经给了（`extension-points.md` 第七节
   还专门解释过为什么不用 `ServiceLoader`），再加一层只会得到两个都不完整的协议。
3. **不做 ABI 级校验**。真正能回答"这个插件能不能跑"的是比对插件引用的方法签名在核心 jar 里还在不在
   （类级字节码扫描）。那是另一个量级的工程，而它要解决的场景与这张票不同：这里要的是
   **作者自己声明过边界**，不是我们替他推断。重估条件：第三方插件多到没人愿意写这一行。
4. **不做类加载器隔离与降级**。插件是同进程的可信代码（README 三条边界里的第二条），
   隔离是 P17 那个方向的事，不是这一张。
5. **不做 `doctor` 那侧的插件清单**。列"装了哪些插件、各自要什么版本"是 P18 的活，
   这张票只在启动时判一次、说清不匹配的那几个。
6. **不校验未声明的插件**。见结构问题 5，这是"不猜"，不是漏掉。

## 门禁余量

实测（`find <module>/src/test -name '*.java' | wc -l`，脚本按 `@Test` 子串数）：

| 模块 | 现在 | 本票之后 | 腾槽办法 |
| --- | --- | --- | --- |
| `claw4j-core` | 10 个文件（顶格） | 10 个 | 合并 `domain/ApproverRosterTest`(4) + `domain/principal/AuthorizationDecisionTest`(11) → `domain/principal/AuthorizationTest`(15)，腾出的槽给 `plugin/PluginContractTest`(13) |
| `claw4j-app` | 10 个文件（顶格） | 10 个 | 闸的 8 条用例并进既有的 `BuildContractTest`(4 → 12)，不新增文件 |
| `claw4j-example-sales` | 1 个 | 2 个 | 直接新增 `SalesPluginContractTest`(1) |

合并那对是同一件事的两端：**谁能做（决策）与谁能批（名单）**，都是 ADR-0007 的鉴权，
现在分在两个包里各写各的。合并时断言原样搬，一条不删——不是重写，是搬家。

**票面原先算错了 capability 的余量**：写票时按 `*Test.java` 数，得到 8 个；
门禁数的是 `*.java`，实际是 **10 个**（8 个测试类 + 2 个 MCP 测试替身）。
这一错把"直接新增"变成了"必须换地方"，也正是它把落点从 capability 挪到了 app。
两个替身都是 public 类（一个文件一个），其余八个测试类两两不同包、都用到包内可见的类型——
没有一处能合法合并，所以 capability 是真的满。

core 的 `CoreBoundariesTest` 会继续守着"`io.github.claw4j.plugin` 只能用 JDK"，
新增的三个类只用 `java.util`，连 slf4j 都不碰。

## 已知局限

- **声明是"作者说的话"，不是事实**。写 `requires-core=*` 的插件永远通过。
  这道闸拦得住"忘了升级"，拦不住"故意糊过去"。
- **只在启动时校验一次**。`loader.path` 不是热加载（`extension-points.md` 第七节实测过三次），
  所以"启动之后换了一层插件"这个场景不存在。
- **不覆盖 ABI 级不兼容**。同一个 minor 里删了一个方法、插件照旧声明着这个区间 → 闸放行，
  然后运行期 `NoSuchMethodError`。口径是"作者承诺的边界"，不是"字节码比对"。
- **没有"这次先放过"的开关**。写错区间的插件会让整个部署起不来，只能改那份描述文件（或换回旧核心）。
  这是有意的：唯一的出路是把声明改对。
- **闸挂在 `claw4j-app` 的启动路径上**。自己另起一套 Spring 启动、只依赖 agent / capability 的用法人
  不会自动获得它，得自己调一次 `PluginContract.checkAll(...)`。判断本身在 core，所以调起来是一行；
  但"忘了调"不会再有人提醒——这条是位置换来的代价。
- **`0.1.0-SNAPSHOT` 与 `0.1.0` 等价**意味着：一个对着 0.1.0 正式版编的插件，
  跑在 0.1.0-SNAPSHOT 上不会被拦。在这个仓库里这是对的（同一个 reactor），
  在别处意味着"预发布与正式版之间我们不做区分"。
- **没声明的不校验**，且**无法**在运行期发现它没声明（见结构问题 5）。

## Comments

### 拆票时核到的四件事（2026-11-04）

1. **清单里没有 `Implementation-Version`**。这一条把"读版本"从一行 `getImplementationVersion()`
   变成了一次构建配置的取舍。实测输出在第一节。
2. **`pom.properties` 只在 jar 里，不在 `target/classes`**。这是"开发态读不到"的洞，
   它会把校验在开发环境里退化成"跳过"，所以最终没用它。
3. **`PathMatchingResourcePatternResolver` 扫得到 jar 里的描述文件**，URI 形如
   `jar:file:/...!/META-INF/claw4j-plugin.properties`。这意味着"插件在另一个 jar 里"这件事
   可以在单元测试里用真 jar 复现——判据里那条就靠它，不用等部署。
4. **`Properties` 原样吃下 `>=0.1.0 <0.2.0`**，不需要转义，也不需要换成冒号分隔。
   这条看着琐碎，但它决定了契约文件的语法能不能直接写成一个可读的区间。

### 实现记录（2026-11-04）

**落点与票面不同的一处**：闸从 `claw4j-capability` 挪到了 `claw4j-app`，理由见「落点」那一节——
capability 的测试文件是真满（10 个，其中 2 个是 public 的 MCP 测试替身，剩 8 个跨包且都用到
包内可见类型，没有一处能合法合并）。判断留在 core，于是"能不能拦"与"在哪拦"分开：
纯计算 3 个类在 `claw4j-core/plugin`，扫描与抛在 `claw4j-app`。

**三处票面没写、实现时补的**：

1. **`PluginContract.checkAll` 收了两个版本参数**（`CoreVersion` 与它原样的字符串）。
   起因是一条用例红了：报错写的是解析后的 `0.1.0`，而构件是 `0.1.0-SNAPSHOT`。
   报错把人看到的版本改写掉，运维会先去核对"我到底部署了什么"。所以比较用前者、消息用后者，
   javadoc 里写清了为什么这不是重复。
2. **`PluginDescriptor` 那个 record 没建**。它只被 `PluginContract` 内部用一次，
   独立出来就是一个没人调的类型——覆盖率上它是纯亏，语义上也只是个三元组。
   直接 `Map<来源, Properties>` 进、`List<Violation>` 出。
3. **`AutoConfiguration.imports` 是 app 模块的第一个**。此前 app 没有任何自动配置（
   只有启动类），这道闸是第一个——于是 `claw4j-app` 从"纯装配"变成"装配 + 一道启动期校验"，
   这是位置换来的第二个代价，已写进已知局限。

**门禁数字又错了一次，错法与前两次不同**：写票时按 `*Test.java` 数 capability 的文件，
得到 8；门禁数的是 `*.java`，实际 10（两个测试替身也算）。P13/P14 错在把 `@Testcontainers`
算成用例，这次错在**漏数了非测试类的 `.java`**。两次的教训合起来是一句：
**门禁怎么数，你就怎么数——别用自己觉得合理的口径去估。**

**如实交代（三条）**：

- **没在真正的 fat jar 里验过**。`classpath*:` 扫 jar 这件事是用临时 jar + `URLClassLoader`
  证明的，`PropertiesBoot` 那种 `BOOT-INF/lib/` 嵌套 jar 的场景没跑过。
  重估条件：第一次用 `loader.path` 装第三方插件时，看启动日志那行"插件版本契约成立：N 个"。
- **`0.1.0-SNAPSHOT` 与 `0.1.0` 等价**是刻意的取舍，不是没做。它的反面（预发布也算不兼容）
  会把同一个 reactor 里的插件挡在门外，见结构问题 4。
- **阈值只有一个**：本票没有"容忍 N 个不匹配"之类的口子，也没有开关。写错区间就是起不来。
