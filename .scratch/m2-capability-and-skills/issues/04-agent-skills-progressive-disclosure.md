# 04: Agent Skills 渐进式披露

**What to build:** 让模型能**选中一个做法并按它编排工具**：`SKILL.md` 描述何时用、按什么流程用；启动时只加载元数据（`name` + `description`），命中才加载全文。Skill 不提供动作，它编排动作（GLOSSARY 的 Skill 定义）。

**Blocked by:** 01

**Status:** ready-for-agent

**Demo:** 问一个命中某 Skill 的问题，模型选中它、按它写的顺序调工具（先用现有的 `querySales` 就够）、给出回答。同时演示"没命中的那个 Skill 的全文没有进上下文"。

- [ ] `SKILL.md` 的加载：启动只加载元数据，命中才加载全文（渐进式披露）
- [ ] 一条断言钉住"未命中的 Skill 全文不进上下文"（断言拼给模型的提示里没有它的正文）
- [ ] 端到端（`AgentPort`）：模型选中 Skill 并按它编排；同步与流式都要
- [ ] Skill 作为工具回调进 01 那条清单断言，并登记 `ToolPermission`（无业务表 + 沙箱目录）
- [ ] 社区包 `spring-ai-agent-utils`（0.13.0）的 `SkillsTool` / `Skills` 在这一张引入，坐标现场核验；`SkillsTool.Builder.build()` 直接返回 `ToolCallback`，不要再包一层自己的工具类型
- [ ] 变异检验：改成"启动时全文加载" → 渐进式披露那条断言红

**注意:** ADR-0008 把 Agent Skills 列为四项做深之二。做深的点不是"能读一个 markdown 文件"，是"命中才加载"与"Skill 编排工具"这两件事各有断言。

**不要做:** Subagent 编排（05）；Skill Generator（06）。
