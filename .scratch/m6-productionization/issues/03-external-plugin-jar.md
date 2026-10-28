# 03: 外部插件 jar 真能装进来吗

**What to build:** 验证 `-Dloader.path` 能不能把**外部目录里的 jar** 加载进已有应用
（不改源码、不重新打包），并把结论 + 起法写进文档。顺带回答"网关类产品是怎么做到新增一个
负载均衡算法不用改源码的"。

**Blocked by:** 无。

**Status:** resolved

## 为什么

README 里那条"`loader.path` 没验证过"挂了两轮。它决定"插件化"是**运维丢个 jar**
还是**改构建再发版**——这是两件完全不同的事，不该靠一句"应该可以"糊过去。

## 要做的实验

1. 打一个插件 jar：一个 `@Tool` 类 + 一个 `@AutoConfiguration` + `META-INF/spring/...imports`，
   只依赖 `claw4j-domain` 与 Spring AI（宿主已有）。工具名刻意取 `pluginPing`，与仓库里
   任何工具都不同，于是"它出现了"这件事可判定。
2. 放 `/tmp/plugins/`，用 `-Dloader.path=/tmp/plugins` 起应用，看 bean 是否被创建。
3. 三组对照，缺一不可：
   - 带 `loader.path` + `PropertiesLauncher` → 期望有
   - 不带 `loader.path` + `PropertiesLauncher` → 期望无（证明插件确实来自那个目录）
   - `java -jar` + `-Dloader.path` → 期望无（证明默认启动器会静默忽略它）
4. 端到端：`/api/chat` 让模型调 `pluginPing`，看它是否真被调到。

## 判据

- 三组对照的结论写进 `docs/design/extension-points.md` 与 README，给出**可复制的命令**。
- 明确写出代价与边界：`PropertiesLauncher` 与 `java -jar` 的差异、插件与核心的版本兼容口径
  仍然没有、工具重名由谁发现。
- 不把"支持插件热加载"写进去：`loader.path` 是启动时扫一次，不是热加载。

## 不要做

- 不改默认打包结构（`layout` 保持 JAR）。实验证明不需要改。
- 不为演示往仓库里提交那个插件 jar 或它的构建脚本；结论进文档，素材留在 `/tmp`。
- 不承诺"任意 Spring Boot 版本都这样"。

## 实现记录

### 结论

**`loader.path` 能用，而且不需要把 `layout` 改成 ZIP。** 在默认的 JAR layout
（`BOOT-INF/classes` + `BOOT-INF/lib`，`Main-Class: JarLauncher`）下，只要显式用
`PropertiesLauncher` 当主类，外部 jar 的自动配置就会被加载。

```bash
java -cp claw4j-app-0.1.0-SNAPSHOT.jar \
     -Dloader.path=/tmp/plugins \
     org.springframework.boot.loader.launch.PropertiesLauncher
```

### 三组对照（Spring Boot 4.1.2 / JDK 21）

插件 jar 内容：一个 `@Tool` 方法 `pluginPing` + 一个 `@AutoConfiguration`（两个 bean，
方法里各打一行 `[plugin] ...` 到 stdout）+ `META-INF/spring/...imports`。只依赖
`claw4j-domain` 与 Spring AI，用宿主 fat jar 里的 `BOOT-INF/lib/*` 当 classpath 编译出来（129 个 jar）。

| 起法 | `[plugin]` 行 | 启动 |
| --- | --- | --- |
| `-Dloader.path=/tmp/plugins` + `PropertiesLauncher` | 两行都有 | 成功（3.4 秒） |
| `PropertiesLauncher`，不给 `loader.path` | 没有 | 成功 |
| `java -jar app.jar -Dloader.path=/tmp/plugins` | 没有 | 成功 |

第二组是关键对照：它证明第一组那两行确实来自 `/tmp/plugins`，而不是"插件碰巧在 classpath 上"。
第三组是那个坑：`java -jar` 用的是 `JarLauncher`，它不认识 `loader.path`，**静默忽略**。

### 端到端

```bash
curl -s -X POST localhost:18081/api/chat -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: ops9' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"调用 pluginPing 这个工具，把「外部插件端到端通了」原样传给它，然后告诉我它返回了什么"}'
```

返回：

```
已调用 `pluginPing`，传入参数为「外部插件端到端通了」。
它返回的内容是：plugin 收到：外部插件端到端通了
```

工具真到了模型手上，不是"只建了个 bean"。

### 网关那类"新增一个负载均衡算法"是怎么做的

不是靠 `loader.path`，是靠 **SPI + 外部 ClassLoader**：核心定义稳定接口与按名字取实现的注册表，
插件 jar 在 `META-INF/services/<接口全限定名>` 里登记实现，启动时用 `URLClassLoader` 扫目录加载，
配置里按名字选中一个。它的语义是"同类实现里选一个"。

Claw4J 的扩展点语义是"多个来源并存，全部交给模型"，所以走 Spring 的自动配置发现更合适，
`loader.path` 已经够用。两者的对照写进了 `docs/design/extension-points.md` 第六节。

### 没做的（如实记）

- **没有把这条实验变成门禁用例**。它需要打 fat jar、在外部目录放一个 jar、再起一个 JVM，
  放进默认构建会让 `verify` 从"跑测试"变成"打两次包再起两次进程"。结论以文档 + 这段记录的形式留下。
- **版本兼容口径仍然没有**。插件编译时依赖的 `claw4j-domain` 版本与核心运行时不一致时，
  没有任何校验，表现是运行期 `NoSuchMethodError`。这一条挂进了 README 的「还没做的」。
- **不是热加载**。`loader.path` 在启动时扫一次；换插件要重启进程。
