# legacy：Claw4J 1.x（已冻结，不参与构建）

这里是 Claw4J 的 1.x 版本，作为 Spring AI 2.x 重建的 **before 对照**保留，**不参与任何构建，不再修改**。

- 冻结位置：`v1.x-legacy` tag
- 技术栈：Spring Boot 3.3.4 / Spring AI 1.0.0-M6，40 个类 / 6793 行 / 零测试
- 根包名：`com.xteammors.openclaw`（新代码是 `io.github.claw4j`，两者不共用）

## 为什么要留着它

重建最有说服力的证据是 before/after 对照。留在这里的东西可以直接指给面试官看：

- **字符串协议的工具调用**：模型输出 `CALL_SKILL: xxx`，Java 侧用 `indexOf` 解析（`TeammorsMessageAdapter`）
- **把 shell 交给模型**：`GenericSkill` 用 `listFiles()[0]` 取一个 Python 脚本，把用户自然语言当 argv 传进去执行
- **反射 hack 的向量库**：反射调 `SimpleVectorStore` 的私有字段，持久化落到工作目录的 `vector_store.json`
- **私有加密通道**：`XMessageClient`（685 行）的 ECDH + AES-GCM 与自建心跳，用来讲"我们如何把通道抽象掉了"
- **自相矛盾的构建配置**：属性写 21、compiler plugin 写 17、`<executable>` 指向不存在的路径

## 不要做的事

- 不要给它加依赖、不要升级它的 Spring 版本、不要在 CI 里构建它
- 不要往这里放任何真实凭据（1.x 曾经有明文 token 与邮箱密码，冻结提交前已替换为占位符）
