# 03: Script Package 受限执行

**What to build:** 让 Agent 用上一个**脚本**能力：脚本本体不进上下文，只有输出进；执行的边界是"只能碰自己的沙箱目录、有超时、非 root、拿不到任何业务凭据"。ADR-0003 把 Script Package 定为三层，ADR-0002 禁止 Java 侧直接拼命令行——这张票是三层里最后一层的第一次落地。

**Blocked by:** 01

**Status:** ready-for-agent

**Demo:** 一个真实脚本包端到端跑通：问一句话 → 脚本被调用 → 输出进回答。同时演示"脚本想碰沙箱目录之外的东西"被挡住，且挡住这件事有一条断言指着。

- [ ] 沙箱约束：工作目录锁在脚本自己的目录内、有超时、非 root
- [ ] 执行环境里拿不到业务库凭据与 API key —— "换个执行位置"不等于"换了个能拿到凭据的位置"
- [ ] 脚本本体不进上下文、只有输出进（GLOSSARY 的 Script Package 定义）
- [ ] ArchUnit 规则：Java 侧不出现 `Runtime.exec` / `ProcessBuilder` / `sh -c`（ADR-0002），靠机器查而不是靠 review 记
- [ ] 脚本层不授予任何业务表权限，登记内容是"无业务表 + 只能访问自己的沙箱目录"；**留空不等于无需权限，而是拒绝**
- [ ] 端到端（`AgentPort`）与流式路径各一条
- [ ] **沙箱的物理形态在这张票定**，判据是"能不能在 CI 里跑起来"：跑不起来的沙箱等于没有测试，没有测试的边界不能写进 README 当承诺
- [ ] 变异检验：放宽工作目录 / 去掉超时 / 漏掉权限登记 → 各自对应的测试红

**注意:** 执行后端用社区包里的 `ExecBackend` 接口（`org.springaicommunity:spring-ai-agent-utils`，0.13.0，根 POM 已在 dependencyManagement 声明但尚无模块引入；`ExecSpec` / `ExecResult` 在它的 `-common` 里）。换沙箱等于换实现，调用方不动——这是这条边界**唯一**可测的地方，不要把"本地执行"写死在调用方。

**不要做:** Skill Generator 那个沙箱（那是另一个执行位置，06）；真实外部 CLI。
