# 重建基线：greenfield 重写，Spring AI 2.0.2 + Spring Boot 4.1.2 + JDK 21

---
status: accepted
date: 2026-10-22
---

Claw4J 的 1.x 版本（Spring AI 1.0.0-M6，6793 行，零测试）存在三类无法增量修复的问题：工具调用靠 LLM 输出 `CALL_SKILL:` 文本再 `indexOf` 解析、脚本执行无沙箱、构建配置自相矛盾（`pom.xml` 属性写 21 / compiler plugin 写 17 / `<executable>` 指向不存在的路径）。我们选择冻结旧代码作为 before 对照，新代码从零构建在 Spring AI 2.0.1 上，而不是原地升级。

## Considered Options

**原地渐进迁移（1.0.0-M6 → 2.0.1）**
否决。升级路径上有两处泥潭：Jackson 2 → 3 的全量序列化行为变化，以及 Boot 3 → 4 的配置属性重命名；而旧代码的收益（字符串协议的工具调用、反射 hack 的向量库）本身就是要被删掉的东西。为删掉的代码付迁移成本不划算。

**Spring AI 2.1.0-M1（Boot 4.2）**
否决。2.1 的增量（structured message parts、OpenAI Responses API）不在本项目主线能力上，而用里程碑版本构建面试项目，无法回答"为什么不上 GA"。等 GA 再评估。

**greenfield 重写（采纳）**
代价是要重新实现一遍已有功能，但换来：不受旧结构约束的模块划分；before/after 本身就是最有说服力的迁移证据；从第一行代码起就带上测试。

## Consequences

- 旧代码打 tag 冻结，不再修改。README 明确标注它是 before 版本。
- 版本基线锁定：**Spring AI 2.0.2**（2.0.x 最新 GA 补丁）、**Spring Boot 4.1.2**、Spring Framework 7、JDK 21、Jackson 3、JSpecify 空安全注解。社区包：`spring-ai-agent-utils` **0.13.0**、`spring-ai-session` BOM **0.10.0**。以上坐标均已对 Maven Central 校验存在。
- `spring-ai-agent-utils` 0.13.0 编译期对齐的是 `spring-ai-client-chat` 2.0.1（provided 作用域，不参与传递依赖），与 2.0.2 同属 2.0.x 补丁线，不构成版本冲突；若后续出现二进制不兼容，回退到 2.0.1。
- 2.0.2 的 BOM 里出现了 `spring-ai-tool-search-advisor` 等新模块（工具检索：不把所有工具塞进提示词，按需检索工具）。它与本项目的场景数量问题直接相关，M2 评估是否接入。
- 旧仓库的 29 个脚本场景不整体迁移（见 ADR-0003）；只挑 8 个代表性场景按新架构重做，其余 21 个删除。
- 迁移期的典型故障（Options 类型 `ClassCastException`、MCP 注解包路径变更）在新代码中提前规避：统一走 `ChatClient` 高层 API，不直接操作 `ChatModel`。
