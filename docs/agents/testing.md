# 单元测试的要求

测试不是"覆盖代码"，是**守住一条坏掉之后不会立刻有人发现的规则**。

ADR-0011 管的是"跑在真模型上、不许跳过"；这一份管"哪些该测、一条测试怎么算合格、留哪个"。

> 本文只写准则。文中的代码片段是从本仓库某一类测试里摘出来的**形状示意**，不指代某个文件——
> 文件会合并、会改名，准则不该跟着漂。判断一段测试属于哪一类，看它验证的方式，
> 不看它叫什么名字。

## 一、四条判据

### 判据一：这条规则坏了，会不会立刻有人知道？

| 坏掉之后 | 例子 | 要不要测 |
|---|---|---|
| 编译不过 | 改了方法签名 | 不测——编译器就是测试 |
| 启动就炸 | 缺 bean、缺 key、目录不存在 | 不测——启动失败比断言更响 |
| 当场异常 | 传 null、空字符串 | 不测——调用处立刻看到 |
| **照样跑，只是结果不对** | 工具静默消失、会话表落到 public、越权放行、脚本参数被 shell 解释 | **必须测** |

绝大多数"没意义的测试"就是在测表格前三行。

### 判据二：测的是机制契约、自身功能，还是模型用不用得起来？

同一个能力可以在两层各测一次，但**三类的对象各不相同**：

| 类别 | 测什么 | 真模型 | 典型内容 |
|---|---|---|---|
| 能力模块内 · 机制契约 | 连接怎么建立、会话丢了是重连还是重试、清单为空为什么必须直接失败 | 不要 | 生命周期与状态迁移 |
| 能力模块内 · **自身功能** | 这个能力自己的业务逻辑对不对 | 不要 | SQL 守卫与只读执行；MCP server 的"参数 → CLI → 结果" |
| Agent 层 | 模型会不会选对这个工具、能不能把自然语言变成对的调用 | 要 | 一句话进 → 一轮工具调用 → 一整段出 |

"能力模块内只测契约"是错的。业务工具是本项目写的逻辑；MCP server 里包的 CLI 调用也是业务逻辑
——把中文参数翻译成命令行参数这一步错了，连接再稳也没用。
契约与功能都要测，只是**都不需要模型**。

判据二真正要防的是：三类里有一类没测，或者两类测了同一件事。

### 判据三：换一种等价实现，这条测试要不要改？

要改，说明它钉的是实现细节，不是行为。

```java
// 反例：把实现抄了一遍。有人把上限从 30s 改成 20s（合法改动），测试跟着改，
// 于是"改测试"变成了"让红灯变绿"的动作，测试失去了独立判断。
assertThat(policy.maxTimeoutMillis()).isEqualTo(30_000);

// 正例：钉行为。上限具体是多少不重要，"超过上限就拒绝、且不夹取"才重要。
assertThatThrownBy(() -> policy.requireTimeoutWithinCap(60_000))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("超过上限");
```

### 判据四：它穿过了多少核心逻辑？

这条决定**先测哪个、留哪个**。同样该测的两条规则，优先测那个穿过多层、被多个入口共用的。

| 入口（方法） | 穿过的逻辑 | 一条测试的收益 |
|---|---|---|
| 一次业务查询 `querySales(...)` | 守卫 → 只读事务 → 行上限 → 结果映射 | 高：五个环节一次走完 |
| 一次鉴权判定 `canCall(...)` | 登记表查询 → 未登记即拒绝 → 只读不可写 | 高：鉴权链的判定核心 |
| 一次 URL 拼接 `urlWithSchema(...)` | 一次字符串拼接 | 低，但它防"悄悄落到 public"，仍要留 |

排序结果是删减的依据：**判据一决定要不要留，判据四决定留哪条、先写哪条。**
入口写成"某个类的某个方法"，而不是"某个文件里有几行断言"——这样红灯亮时，
顺着方法就能走完整条链路，这才是调试时能用的测试。

## 二、正面案例

### 1. 钉"反直觉的那一侧"，不是"显而易见的那一侧"

```java
@Test
@DisplayName("注释里的分号不构成多语句")
void 注释里的分号() {
    // 字符串层面看有分号，数据库层面它是一条语句。不先去注释就会误拒，
    // 误拒的方向是拒绝，但会让模型莫名其妙。
    assertThat(SalesSqlGuard.rejectionOf("select 1 from business.region -- ; drop table t")).isEmpty();
}

@Test
@DisplayName("可写 CTE 会被拦下：with 开头不等于只读")
void 可写cte拒绝() {
    // PostgreSQL 允许 with 里做 insert/update/delete 再 returning 出来。
    // 任何"首关键字是 select 或 with 就放行"的规则都会放过它。
    assertThat(rejectionOf("""
            with doomed as (delete from business.sales_order returning *)
            select * from doomed""")).contains("delete");
}
```

「普通 `select` 放行」那一条谁都会写，坏了立刻被端到端抓到。
值钱的是上面两条：一条防误拒，一条防漏放，**都只在模型恰好那么写时才暴露**。

### 2. 钉"静默失效"

```java
@Test
@DisplayName("第二个工具来源进来时，第一个来源的工具仍在容器里")
void 第二个来源不会挤掉第一个() {
    // 缺陷姿势是"工具静默消失"——不报错、不告警，只是模型手上没有那个工具，
    // 于是它开始编答案。表现是"模型变笨了"，没有任何一行日志指向原因。
    contextRunner
            .withBean("probeProvider", ToolCallbackProvider.class, ...)
            .run(context -> assertThat(toolNames(context)).contains("querySales", "probe"));
}
```

同一形状的规则还有两条，方法名本身就该是判据：

- 「配了目录却不存在 → 启动失败」，而不是"这一层没启用"；
- 「某一份定义写坏了 → 整层起不来」，而不是静默少一个；
- 「schema 是空串 → 直接拒绝」，因为不报错只会让会话表悄悄落到 public。

这三条的共性是：**代码照跑、日志干净、结果错**。判据一里最贵的一格。

### 3. 同一个边界分成"算得对"和"接上了"两组，这不是重复

第一组是纯函数，一个进程都不起：

```java
@Test
@DisplayName("指向目录外的符号链接被挡住")
void 符号链接被挡住(@TempDir Path root, @TempDir Path outside) throws IOException {
    Path secret = outside.resolve("secret.txt");
    Files.createFile(secret);
    Files.createSymbolicLink(root.resolve("leak.txt"), secret);

    assertThatThrownBy(() -> policy.resolveInside(root, "leak.txt"))
            .isInstanceOf(SandboxViolationException.class)
            .hasMessageContaining("符号链接");
}
```

第二组起真子进程，看约束到底有没有落到进程上：

```java
@Test
@DisplayName("进程的工作目录锁在脚本包自己的目录里")
void 工作目录锁在包目录内(@TempDir Path parent) throws IOException {
    ScriptPackage pkg = ScriptPackageFixtures.executable("cwd", parent,
            ScriptPackageFixtures.script("pwd"));

    ProcessOutcome outcome = SandboxedProcessBackend.forPackage(pkg).run(requestFor(pkg));

    // macOS 上 /var 是 /private/var 的软链，两边都要 realpath 才比得动
    assertThat(Path.of(outcome.stdout().strip()).toRealPath()).isEqualTo(pkg.root().toRealPath());
}
```

两组的红对应两种完全不同的毛病：**前一组红 = 约束没写；后一组红 = 约束没接到进程上**。
换假的执行器只能验到"我们调没调用它"，验不到"进程到底起在哪儿"。
它们看起来在测同一组边界（路径 / 超时 / 非 root / 环境），但一个验规则、一个验落地。

**判断是不是重复，不能看方法名像不像，要看它怎么验证、坏了分别意味着什么。**

## 三、反面案例

### 1. 测框架行为，不是测自己的代码

```java
@Test
void 装配时带上系统提示词不影响用户消息传递() {
    ChatClient client = ChatClient.builder(chatModel())
            .defaultSystem("你是助手，用户说什么你就原样复述那一个词，不要加别的")
            .build();

    assertThat(client.prompt("苹果").call().content()).contains("苹果");
}
```

这条红了说明框架坏了，而框架坏了会有它自己的测试。
它还花掉一次真模型调用。**别人的代码不该由我们的测试守。**

反过来，同一个装配文件里如果有一条守"流式被降级成一次性返回"的，那条要留——
那是我们接错装配才会发生的事，框架不会替我们发现。

### 2. 测配置透传

```java
@Test
void 装配出来的模型带着配置的 model 名() {
    assertThat(model.getOptions().getModel()).isEqualTo("deepseek-chat");
}
```

这只能证明"配置读进来了"，读进来之后**有没有用上**它管不着。
要证明配置起了作用，得钉行为——比如温度和随机性的关系：
温度不归零，同一条提示每次答案都不同；断言"两次相同"就是在证明零度真的生效了。

另一类要留的是"启动失败并说清去哪配"：它们守的是"没有 key 就红，绝不退到假模型"，
每一种配错姿势对应一个用户可感知的后果，所以各写一条，不合并。

### 3. 测一行代码的派生结果，且有更高层覆盖

```java
@Test
void 会话标识是确定的() {
    assertThat(SessionKeys.sessionIdOf(user))
            .isEqualTo(SessionKeys.PREFIX + "user")
            .doesNotContain(CHAT_ID);
}
```

`sessionIdOf` 的实现就是 `PREFIX + userId`，这条测试把那一行抄了一遍。
"跨通道不失忆"这件真事，要由端到端用两个不同 chatId 走一遍端口来证明。

反过来，同一处"身份为空就拒绝"的那条要留：它防的是"所有人共用一个会话，记得是别人的"
——那是静默的，而且删掉一行代码也不会报错。

## 四、条数怎么降：靠删，不靠合并

降条数只有一个正当手段：**删掉不守任何东西的测试**。其余手段都只是把问题藏起来。

### 1. 合并成一条，问题没少，只是红灯变模糊了

cgroup 的内存上限有三条规则，说的是三件不同的事：

```java
@Test
@DisplayName("v2 的 memory.max 是数字时按字节解析")
void v2数字() {
    assertThat(limitsOf(V2_MEMORY_MAX, "536870912").memoryLimitBytes()).isEqualTo(536870912L);
}

@Test
@DisplayName("max 是「没设限」，不是「限额是 max」")
void v2写max就是没设限() {
    // 把 max 当数字用出去，模型会照着"限额是 max"去解释一次 OOM
    assertThat(limitsOf(V2_MEMORY_MAX, "max").memoryLimitBytes()).isEqualTo(UNKNOWN);
}

@Test
@DisplayName("v1 那个天文数字是「没设限」，不是「限额 9.2EB」")
void v1哨兵值就是没设限() {
    // v1 没设限时写 9223372036854771712，当成字节数就会报出一个假的 9.2EB 上限
    assertThat(limitsOf(V1_MEMORY_LIMIT, "9223372036854771712").memoryLimitBytes()).isEqualTo(UNKNOWN);
}
```

压成一条参数化之后：

```java
@ParameterizedTest(name = "{0}")
@CsvSource(delimiter = '|', value = {
        "v2 数字   | /sys/fs/cgroup/memory.max                   | 536870912           | 536870912",
        "v2 写 max | /sys/fs/cgroup/memory.max                   | max                 | -1",
        "v1 哨兵值 | /sys/fs/cgroup/memory/memory.limit_in_bytes | 9223372036854771712 | -1",
})
void 内存上限(String scene, String file, String raw, long expected) {
    // UNKNOWN 就是 -1
    assertThat(limitsOf(file, raw).memoryLimitBytes()).isEqualTo(expected);
}
```

方法数从 3 降到 1，**断言一条没少**。但红灯从

```
v2写max就是没设限()：max 是「没设限」，不是「限额是 max」
```

变成了

```
内存上限(String, String, String, long) [2] v2 写 max
```

后者只告诉你"第 2 组数据挂了"。这条规则为什么存在、挂了意味着什么、该去哪修——
原本全在方法名和 `@DisplayName` 里，现在被压进 CSV 的一行字符串。**条数降了，信息也降了。**

### 2. 什么时候参数化是对的

判据一句话：**这几行数据红了，是不是同一个原因、修同一处代码？**

| 场合 | 能参数化吗 | 为什么 |
|---|---|---|
| `drop / delete / update / truncate` 都该被守卫拦下 | 能 | 任一行红都是"关键字表漏了一个"，修的是同一处 |
| 同一个解析函数的几组等价取值 | 能 | 失败含义相同，只是输入不同 |
| `v2 写 max` 与 `v1 哨兵值` | **不能** | 前者红 = 没认 v2 的 `max`，后者红 = 没认 v1 的哨兵值；两个平台、两处代码，连将来变化的时间线都不同（v1 迟早淘汰） |

所以参数化是"**一组取值共享同一句规则**"时的自然写法，不是降条数的手段。
为了把数字做小而参数化，等于把规则藏进字符串。

### 3. 那到底怎么降

删。删的对象是前三条判据筛掉的三个类别：测框架的、测配置透传的、测一行代码且上层已覆盖的。

删完剩下多少就是多少。**数字是结果，不是目标。** 某个模块确实有十几条规则合判据一，
那就留十几条，不为凑数砍。

## 五、配额（估算，不是硬指标）

| 层级 | 量级 |
|---|---|
| 每个模块 | 3–7 个测试文件 |
| 每个测试文件 | 5–10 个测试方法 |

超过这个量级时先问：多出来的是新规则，还是同一规则的新取值？
新规则且合判据一 → 留；新取值 → 同一个方法里多个断言；两者都不是 → 删。

配额按**层**算，不按模块的文件总数算：

| 层 | 一份文件管什么 | 怎么算 |
|---|---|---|
| 单元层 | 一条规则、一个机制的契约 | 按上面两张表的量级 |
| 验收层（`*EndToEndTest`） | 一个能力"整条走得通" | 一个能力一份，不计入单元层的配额 |

两层不能混着数。验收层吃的是真实库、真实模型、真实进程，代价就是文件数；
换来的是"哪一环断了"能靠文件名直接定位。硬把它压进单元层的配额里，
要么牺牲覆盖，要么把三个能力的夹具塞进一个类——后者更糟。

## 六、什么时候该动旧测试

改动代码时，测试不是"别的地方的东西"，是同一次提交的一部分：

- 改红线或主流程 → **同一次提交**里同步更新测试；
- 删掉一条测试 → 提交信息写明为什么它不再守任何东西；
- 新增一条能力 → 先按判据二分清"契约 / 自身功能 / 模型可用性"，再决定写在哪个模块。

判断一条旧测试还有没有用，不看它跑得快不快，看两件事：
**把被测的那行代码删掉，它会红吗？红了之后，有用户可感知的后果吗？**

## 七、测试啰嗦，往往说明代码该改

| 症状 | 设计信号 |
|---|---|
| 三条测试都在搭同一套复杂前置 | 入口方法参数太多，或依赖没有接缝 |
| 断言要绕过封装（反射 / 读私有字段） | 行为没有被公开，或者这个类不该这样拆 |
| 十方法测同一操作的取值矩阵 | 返回值太"软"（裸 String / 裸 long），没有类型承载语义 |
| **同一层内**三份测试覆盖同一组边界 | 边界写在三个地方，该收进一个值对象 |

注意最后一行的限定：跨层分组（纯函数一组、真进程一组）不算重复，那是诊断分层。

收敛测试的过程会反过来要求改主代码。这是好事，不是副产品——
**一份需要 15 条测试才能说明白的设计，讲给面试官听也要讲 15 遍。**

## 八、提交前自检五问

1. 把被测的那行代码删掉，这条测试会红吗？
2. 红了之后，有用户能感知的后果吗？
3. 换一种等价实现，这条测试要不要跟着改？（要改 = 钉了实现细节）
4. 它测的是我们的代码，还是框架的行为？
5. 它守的规则，在别的层已经测过了吗？
