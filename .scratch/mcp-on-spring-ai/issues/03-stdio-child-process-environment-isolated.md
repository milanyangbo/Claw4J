# 03: stdio 子进程只拿显式声明的环境变量

**What to build:** `ProcessStdioMcpEndpoint` 的类注释曾写着"不传就是空表，子进程拿不到应用进程的
环境，应用的模型 key、数据库口令都不会漏过去"。那句话**是假的**：SDK 的
`StdioClientTransport.connect()` 用 `new ProcessBuilder()` 起进程，再
`environment().putAll(params.getEnv())`，父进程环境整份继承（`mcp-core 2.1.0`
`StdioClientTransport.java:145-148`，没有 `clear`）。

本票二选一：兑现它，或者把话改成实话。

**Blocked by:** 一次裁决（见 `## Answer`）—— 八行的实现已写好并被红线拦下。

**Type:** task

**Status:** blocked

## 裁决来源

- `ProcessStdioMcpEndpoint` 类注释与 `McpServerProperties.Server#inheritEnv` 的注释。
- `docs/adr/0002-script-execution-via-mcp-with-sandbox.md`：子进程边界上环境是四条约束之一。
- `capability/ToolsBoundaryTest#onlyLocalProcessRunnerStartsProcesses`：**起进程只经
  `LocalProcessRunner` 与 `ShellCommandRunner` 两个类**，规则注释原文："多出第三个时这条规则会红，
  那是要一次裁决的信号"。

## Answer

**能兑现，但那八行会触碰一条红线，所以停下等裁决。**

写完的实现（8 行，已验过红绿）：

```java
private static final class DeclaredEnvironmentOnly extends StdioClientTransport {

    private DeclaredEnvironmentOnly(ServerParameters parameters) {
        super(parameters, McpJsonDefaults.getMapper());
    }

    @Override
    protected ProcessBuilder getProcessBuilder() {
        ProcessBuilder builder = super.getProcessBuilder();
        builder.environment().clear();   // 父类随后 putAll(params.getEnv())
        return builder;
    }
}
```

配一条跨进程用例（`sh -c 'env > "$OUT"'`，真起子进程读回环境）验过：

- 带 `clear()` → 绿，子进程只有 `PATH` 与显式声明的 `OUT`；
- 删掉 `clear()` → **只有这一条红**，报错点名 `CODEBUDDY_ARTIFACTS 不该出现在子进程里`。

但 `ToolsBoundaryTest` 直接红：

```
Rule 'noClasses()...should().accessClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")'
was violated (1 times)
```

覆盖 `getProcessBuilder()` 的返回类型就是"访问 `ProcessBuilder`"，而这条规则守的是
ADR-0002 那条**全称**保证（"起进程只有两处"）。加第三个例外是一次裁决，不由本票自己拍。

## 三种走法（等一句话）

| 走法 | 代价 |
| --- | --- |
| A. 把这一处加成第三个例外 | 红线从"两处"变"三处"；但 MCP 的进程是 **SDK 起的**，我们只清环境，与 `LocalProcessRunner`（自己起进程）不是一类事 |
| B. 保持继承父环境，只把注释改成实话（**已落地**） | 隔离靠部署层的容器策略；MCP server 子进程能看到应用的全部环境变量 |
| C. 用 `env -i` 包裹启动（`command: env`，`args: [ -i, KEY=VAL…, 真命令 ]`） | 不改 `ProcessBuilder`，但改写 argv（`stdioKeepsTheArgvExactlyAsWritten` 会红）、依赖 POSIX 的 `env`、Windows 上不成立 |

## 这次落地的是 B

- `ProcessStdioMcpEndpoint` 类注释改成实话：显式声明的**一定在**，父进程其余变量**也仍在**，
  并写明"这里不承诺隔离"、需要隔离时那八行在哪、为什么没做。
- `McpServerProperties.Server#inheritEnv` 里"给多了应用的模型 key 就漏过去了"这句一并改掉：
  它暗示了隔离，而隔离不存在。`inherit-env` 的真实语义是"按名字补几个进去"。
- 没有新增用例：B 只是把文档对齐现状，行为一行没变；那条跨进程用例跟着实现一起撤了，
  免得出现"用例在验一件没做的事"。

## 实现记录（A 做完的话从这里接）

`DeclaredEnvironmentOnly` 那八行在 `git log` 里（本票的第一次提交），恢复它 + 恢复
`McpServersConfigTest#childProcessOnlySeesDeclaredVariables`（当时的做法：`sh -c 'env > "$OUT"'`，
`initialization-timeout` 1 秒，断言 `PATH=`/`OUT=` 在、父进程独有变量不在），再把这条类名加进
`ToolsBoundaryTest` 的例外即可。三条一起改，少一条都不成立。
