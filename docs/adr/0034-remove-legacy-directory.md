# 移除 legacy/ 目录：before/after 对照改由 tag 提供

---

status: accepted
date: 2026-11-21

---

ADR-0004 把 1.x 源码搬进仓库根的 `legacy/`，理由是「一次 clone 就能看到 before 与 after 的对照」。这条理由现在不成立了。

## Context

`legacy/` 里是 1.x 的 104 个文件、2.3 MB，冻结在 `v1.x-legacy` tag 上，不参与 Maven 构建。留它的唯一目的是对照。

三个事实让它变成了纯负担：

- **对照已经不需要它。** 这轮重建真正引用 1.x 的地方只有两处：一处是 ADR 里「1.x 是怎么做的」这类叙述，一处是代码注释里指到 `GenericSkill.java:206` 的行号。前者不需要源码在场，后者反而因为行号会漂而变成死链。
- **它在拖慢日常动作。** 全局搜索、IDE 索引、`grep -r` 都要显式排除它，而排除规则靠人记。搜到 `com.xteammors` 的命中要先判断「这是冻结代码还是我在找的东西」，每次都要多一步。
- **它已经开始腐烂。** 里面有 `skills/`、两张截图、一份自己的 `README.md`，与现行文档体系（`docs/adr`、`docs/design`、`CONTRIBUTING.md`）没有交叉引用。留着它等于留着一份没人维护的第二套说明。

## Decision

**从工作区删除 `legacy/`，1.x 源码只由 `v1.x-legacy` tag 承载。**

tag 是完整快照，取回成本一条命令：

```bash
git show v1.x-legacy:legacy/src/main/java/com/xteammors/openclaw/skills/GenericSkill.java
git worktree add /tmp/v1x v1.x-legacy
```

对照能力没有下降——**只要愿意敲一条命令，证据仍在**；而平时不再为它付搜索与索引的代价。

## Considered Options

**保留（现状）**
否决。理由见上：收益是一次 clone 的便利，成本是持续的搜索噪声与一份无人维护的文档副本。

**移到独立仓库**
否决。拆出去之后取回成本比一条 `git worktree` 高得多，而它已经不再参与任何构建或对照，不值得再养一个仓库。

**删除工作区副本，保留 tag（采纳）**
代价是「clone 下来就能看」变成「clone + 一条命令」。认为这个代价可以接受：真正需要翻 1.x 的场景是讲演进，那时本来就在终端里。

## Consequences

- `CONTRIBUTING.md` 里「`legacy/` 是冻结代码」那段改成「已移除，冻结在 `v1.x-legacy` tag」，并给出取回命令。
- `ProcessStdioMcpEndpoint` 的类注释原来指着 `legacy/.../GenericSkill.java:206`，改为不依赖路径的叙述（「1.x 把参数原样拼进 argv 末尾」），只提 tag 名。
- **ADR-0008 与 ADR-0009 里「它的 ECDH/AES 代码保留在 `legacy/` 里作为对照」这句话随之失效**——对照改为指向 tag，结论（那条通道排在最后、讲不出通用价值）不变。
- `ADR-0004` 整篇作废（它决定的就是「把 1.x 放进 `legacy/`」），已在文首标注。
- `.scratch/` 下的历史 spec 与 memory 日志不改：它们是当时的记录，不是现行说明。
- 仓库根只剩 `claw4j-*` 模块，全局搜索不再需要排除规则。
