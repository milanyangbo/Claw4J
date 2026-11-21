# 票 11：白名单 policy.allow

状态：**待做**。裁决：`docs/adr/0035-model-gateway-module.md` 第六节。

## 一、目标

给"哪些模型可以被选中"一个显式约束，形状照 OpenClaw 的 `modelPolicy.allow`：精确 ref + 尾部通配。
我们没有 operator 角色，所以只做配置层这一层。

## 二、范围

| # | 改什么 | 要点 |
| --- | --- | --- |
| 1 | `claw4j.model.policy.allow`（列表） | 不配或 `[]` = 放行全部 |
| 2 | 三种写法 | 精确 `provider/model`；段前缀 `provider/*`、`provider/namespace/*`；模型名前缀 `provider/family-*`。**只认尾部 `*`**，段内不许出现 `*` |
| 3 | 显式选择被排除 → **启动失败并列出允许集** | 与它不同：它的"自动选择"会退到第一个被允许的模型并 WARN；我们没有自动选模型这个场景 |
| 4 | `fallbacks` 不受 allow 过滤 | 它的注释原话：*explicit user intent* |
| 5 | 解析失败（中间通配、空段、控制字符）启动即抛 | 不静默忽略 |

## 三、对照（OpenClaw）

| 它的形态 | 位置 |
| --- | --- |
| `agents.defaults.modelPolicy.allow`：接受别名、精确 ref、尾部通配；不配或 `[]` 放行；per-agent 覆盖 | `docs/gateway/config-agents/models.md:128` |
| 通配解析：只认尾部 `*`；段前缀或模型名前缀；段内不许有 `*` | `src/config/model-policy-ref.ts:29-49` |
| fallbacks 不过滤 | `src/agents/model-fallback-candidates.ts:388-390` 注释 |
| 自动选择被排除 → 取第一个被允许的并 WARN；显式覆盖仍失败 | `docs/concepts/models.md`「Other selection rules」 |

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 不配或空列表时全部放行 | `ModelPolicyTest#emptyAllowListAllowsEverything` | 拒绝一切 |
| 精确 ref 只放行那一个 | `ModelPolicyTest#exactRefAllowsOnlyThatModel` | 通配误放 |
| `provider/*` 放行该家全部模型 | `ModelPolicyTest#providerWildcardAllowsFamily` | 只匹配到第一个 |
| `provider/family-*` 按模型名前缀匹配 | `ModelPolicyTest#modelPrefixWildcardMatchesByName` | 当成段前缀 |
| 中间通配（`prov*/x`）启动即抛 | `ModelPolicyTest#midWildcardIsRejected` | 接受并静默不匹配 |
| 显式 primary 被排除 → 启动失败并列出允许集 | `ModelPolicyTest#excludedPrimaryFailsAtStartup` | 静默换成别的模型 |
| fallbacks 里被排除的模型仍可被调用 | `ModelPolicyTest#fallbacksIgnorePolicy` | 被过滤掉 |

## 缺口

- 别名（`agents.defaults.models`）不做：我们还没有别名表。
- per-agent 覆盖（`agents.entries.*.modelPolicy`）不做，等 per-agent 模型覆盖那张票。
