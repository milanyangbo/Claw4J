## 1. 项目结构（铁律：目录不许乱建）

本项目是 Maven 多模块工程，模块边界已定死：
claw4j/
├── claw4j-common/ # 公共组件（工具类、异常、注解、DTO）
├── claw4j-api-gateway/ # 接入层（8080）
├── claw4j-orchestrator/ # 编排层（8081）
├── claw4j-tool-executor/ # 工具层（8082）
├── claw4j-knowledge-memory/# 知识层（8083）
└── claw4j-a2a-broker/ # 协作层（8084）

**禁止行为**：
- 禁止在根目录或模块下新建任何新目录（如 `utils/`、`helpers/`、`services/`、`impl/`、`test/` 等）
- 禁止新建模块（除非提案明确要求）
- 禁止在 `src/main/java` 下创建不属于 `com.claw4j.<模块名>` 的包路径

**包的归属**：
- 代码必须放在 `com.claw4j.<模块名>` 包下
- 子包只能从以下列表中选择：`controller` / `service` / `repository` / `config` / `dto` / `entity` / `exception` / `util` / `annotation`
- 如果确实需要新子包，必须在代码注释中说明理由，并在 PR 描述中标注

## 2. 代码风格（必须严格遵守）

### Java 规范
- 所有类必须有 Javadoc 类注释（说明职责，一句话即可）
- 所有 public 方法必须有参数和返回值说明
- 禁止使用 `System.out.println`，统一用 Slf4j
- 禁止吞异常（空 catch 块）
- 禁止 `return null`，用 `Optional` 或抛业务异常
- 魔法数字必须提取为常量或枚举

### Spring Boot 规范
- 禁止使用 `@Autowired` 字段注入，统一构造器注入
- `@Service` / `@Component` 必须有明确的职责边界，一个类只做一件事
- Controller 层只做参数接收和返回，业务逻辑必须在 Service 层
- Feign 接口必须定义 `fallback`，不允许裸调用

### 防御性编程（本项目核心风格）
- 所有外部输入（用户输入、RAG 检索结果、工具返回）都必须校验
- 所有 Redis Key 必须包含 `tenantId:userId` 前缀
- 所有工具调用必须包含幂等键
- 所有模型调用必须受步数/Token/超时预算约束
- 禁止在工具执行中使用 Post-filter，必须用 Pre-filter

## 3. 禁止事项（碰了就重写）

1. **禁止无提案写代码**：所有新功能必须先有 OpenSpec 提案（用户会提供），没有提案时不要擅自扩展。
2. **禁止"顺手重构"**：只能修改 tasks.md 中列出的文件，发现需要重构的地方先报告，不要动手。
3. **禁止硬编码**：密钥、Token、IP、端口、魔法数字一律通过配置文件或常量管理。
4. **禁止跨租户数据访问**：任何涉及数据的操作，必须先确认租户隔离是否正确。
5. **禁止绕过防御机制**：不写绕过预算熔断、限流、权限检查的代码。
6. **禁止把用户输入直接拼进 System Prompt**：必须用 `<user_query>` 标签包裹，视为纯数据。
7. **禁止创建非标准目录**：这是本项目最常见的问题，每次创建文件前先确认目标目录是否已存在。

## 4. 代码提交规范

- 每个 task 完成后，运行 `mvn clean compile -pl <模块名>` 确保编译通过
- 运行相关单元测试，失败则修复后再提交
- 提交信息格式：`[ChangeID] 任务描述`（如 `[add-tool-rbac] 实现工具级权限校验`）

## 5. 详细规范引用

需要查看更多细节时，读取以下文件：
- 项目上下文与架构：`openspec/project.md`
- 完整工程防御规则：`openspec/specs/defense-rules.md`
- 安全护栏细节：`openspec/specs/security-rules.md`