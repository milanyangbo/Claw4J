# Triage Labels

The skills speak in terms of five canonical triage roles. This file maps those roles to the actual label strings used in this repo's issue tracker.

| Label in mattpocock/skills | Label in our tracker | Meaning                                  |
| -------------------------- | -------------------- | ---------------------------------------- |
| `needs-triage`             | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`               | `needs-info`         | Waiting on reporter for more information |
| `ready-for-agent`          | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `ready-for-human`    | Requires human implementation            |
| `wontfix`                  | `wontfix`            | Will not be actioned                     |

When a skill mentions a role (e.g. "apply the AFK-ready triage label"), use the corresponding label string from this table.

## Applying a label in a local-markdown tracker

There is no label API here. Apply a role by writing or editing the `Status:` line near the top of the issue file:

```markdown
Status: ready-for-agent
```

Drop the line entirely (or leave it empty) for an untriaged issue. `Status: claimed` / `Status: resolved` are the `/wayfinder` lifecycle states and are not triage roles.

## 本仓库实际在用哪套词（2026-10-25 数过 21 张票 + 4 份 spec）

两套词各有归属，不要混着写：

- **`spec.md` 头部的 `Status:`** 用上面那张表的 triage 角色。四份 spec 现在都是 `ready-for-agent`。
- **`issues/NN-*.md` 的 `**Status:**`** 走三个状态，够用就不要加第四个：
  新票发布时 `ready-for-agent` → 被领取后 `claimed` → 收票时 `resolved`。
  前两个之外的词（`open`、`todo`、`doing`）都不要用。（2026-10-26 起：新票发布即写 `ready-for-agent`，
  在此之前票只有 `claimed` / `resolved` 两个状态，已完成的 21 张全是 `resolved`。）

`closed` 出现过一次（M2 的八张票），已经统一回 `resolved`。`/wayfinder` 只认 `resolved`，
自造一个同义词会把"还有哪些票没做完"变成要人去辨形的判断题。
收票时状态词只能从上面两行里挑一个。
