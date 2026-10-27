# 04: 把"实体"编进缓存上下文键，替掉事后否决

**What to build:** 命中判定里的"时间词与数字必须一致"从**取到最近邻再否决**，
改成**不在同一个上下文里，压根不互相检索**。

**Status:** resolved（2026-10-27）

## 为什么改

改之前，`CacheAdvisor.answerFor` 是这么判的：

```java
Optional<Candidate> candidate = cache.nearest(fingerprint, question, embedding);
if (candidate.isEmpty()) return miss(...);
if (found.similarity() < threshold) return miss(REASON_BELOW_THRESHOLD);
if (!sameEntities(question, found.question())) return miss(REASON_DIFFERENT_ENTITIES);  // ← 补丁
```

`sameEntities` 是"把最近邻捞出来，再看两句话的时间词和数字对不对得上"。它工作，
但它是**流程里额外加的一道判断**——框架里没有对应物。而框架有一套自己的做法：
`SemanticCache` 接口带一个 `contextHash`，"不同上下文下的同一个问题是两条记录"。
框架自己填的是 SHA-256(系统提示词)。

所以这次不是加功能，是把判断**挪到框架已有的位置上**：时间词与数字成为上下文的第二段，
与权限指纹并列。`answerFor` 里那道 `sameEntities` 随之删掉。

## 改了什么

**存储**（`V5__semantic_cache_context.sql`）：

```sql
alter table claw4j_cache.semantic add column if not exists entities varchar(64) not null default '';
delete from claw4j_cache.semantic;   -- 旧行没有实体签名，无法判断归到哪一组；缓存可丢
create unique index uq_semantic_scope_question on claw4j_cache.semantic (fingerprint, entities, question_key);
```

索引也换了：唯一键从 `(fingerprint, question_key)` 变成 `(fingerprint, entities, question_key)`。

**接口**（`SemanticCache`）：`nearest` / `store` 各多一个 `entities` 参数，
并新增 `nearestInFingerprint`——同权限、不限实体，**只用于判断 miss 的原因**。

**上下文键的计算**（`CacheAdvisor.entitySignature`）：

```java
public static String entitySignature(String question) {
    return PermissionFingerprint.sha256(
            String.join(",", tokens(TIME_WORDS, question))
                    + "#"
                    + String.join(",", tokens(NUMBERS, question)));
}
```

时间词正则保留原样（`上上周` 必须排在 `上周` 前面，否则 `find` 会从第二个字开始匹配到"上周"）。

**miss 原因的三段判定**：上下文里没候选 → 先查"同权限、任意实体"（有则 `different-entities`）
→ 再查全库（有则 `different-permission`）→ 都没有才是 `no-candidate`。
两次额外查询只在 miss 路径上，而 miss 之后就要打一次模型，两条 SQL 可以忽略。

## 不做什么

**不把区域加进实体。** "上周华东"与"上周华南"确实也是两个不同的问题，
但区域名是一张越列越长的枚举（省、市、大区、门店），今天加"华东华南"，
明天就要加"东区""北区"。那正是这次要避免的补丁形态。
区域这一档仍然靠阈值挡（实测 0.8968 / 0.9057，低于 0.93）。
真要按区域分，正确做法是让工具返回里带上"这次查了哪个区域"，而不是继续在问题文本里猜。

## 验证

**单元**（`SemanticCacheTest`，11 条）：新增 `entitySignatureSeparatesTimeWindows`
——同义改写签名相同、换时间窗签名不同、换月份签名不同、闲聊的空签名相等。

**变异**：把 `entitySignature` 改成恒返回常量（等于取消实体维度），
`differentTimeWordMissesEvenThoughItLooksSimilar` 与 `entitySignatureSeparatesTimeWindows` 变红，
前者报的是 `Expected size: 2 but was: 1`——**它真的没打第二次模型，直接命中了上周的答案**。

**真链路**（固定端口 18080，真实数据库，V5 在真库上 `Successfully applied`）：

| 提问 | 时延 | 结果 |
| --- | --- | --- |
| A 上周华东区成交额多少 | 3060ms | 打模型，答案 99,999.00，写入 `entities=80ad7f594de1` |
| B 上上周华东区成交额多少 | 2537ms | 打模型，答案 **128,400.00**，写入 `entities=ecfe98d25935` |
| C 上周华东区成交额多少（原句） | **224ms** | 命中，带「（同上次）」，没打模型 |

指标：`no-candidate=1`（A）、`different-entities=1`（B）、`hits=1`（C）。
B 的答案是**上上周的真值**，不是抄 A 的 99,999——这正是这次改动的目标。

**全量**：`./scripts/verify.sh` → 231 条用例、失败 0、跳过 0。

## 顺带记下的坑

`SemanticCache` 接口里带 `contextHash` 的那两个方法是 `default`，默认实现直接调两参版本、
**把 `contextHash` 丢掉**。实现类只实现 `abstract` 方法的话，隔离会静默失效。
已写进 `docs/design/pitfalls.md` 第十三条。
