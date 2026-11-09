# Issue tracker: Local Markdown

Issues and specs for this repo live as markdown files in `.scratch/`.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`
- The spec is `.scratch/<feature-slug>/spec.md`
- Implementation issues are one file per ticket at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01`, never a single combined tickets file
- Triage state is recorded as a `Status:` line near the top of each issue file. Which vocabulary that line takes depends on the file: `spec.md` uses triage roles, `issues/NN-*.md` uses the wayfinder lifecycle (`claimed` / `resolved`). See `triage-labels.md` for both, and don't invent a third word
- Comments and conversation history append to the bottom of the file under a `## Comments` heading
- 每张票面必须有两节：**裁决来源**（一行指到 ADR / `docs/design/`，不在这里重复论证）与
  **TDD 用例清单**（每条功能点一行：判什么行为 → 用例名 → 先红的样子）。一时补不上的判据，
  单列一节写"缺口"，不许勾进验收——勾了就是文档承诺比代码走得远
- Planning text in a ticket is a **forecast until it is a fact**: write "预计落在 X" as 预计, and correct it when the work lands. A bare "落在 X" gets read as a requirement by the next reviewer — 票 05 的"门禁余量"一节预计落 `KnowledgeRetrievalTest` / `ChatPipelineTest`，实际落在另外三个文件，评审时就被报成了"文档没同步"

## When a skill says "publish to the issue tracker"

Create a new file under `.scratch/<feature-slug>/` (creating the directory if needed).

## When a skill says "fetch the relevant ticket"

Read the file at the referenced path. The user will normally pass the path or the issue number directly.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a file with one **child** file per ticket.

- **Map**: `.scratch/<effort>/map.md` (the Notes / Decisions-so-far / Fog body).
- **Child ticket**: `.scratch/<effort>/issues/NN-<slug>.md`, numbered from `01`, with the question in the body. A `Type:` line records the ticket type (`research`/`prototype`/`grilling`/`task`); a `Status:` line records `claimed`/`resolved`.
- **Blocking**: a `Blocked by: NN, NN` line near the top. A ticket is unblocked when every file it lists is `resolved`.
- **Frontier**: scan `.scratch/<effort>/issues/` for files that are open, unblocked, and unclaimed; first by number wins.
- **Claim**: set `Status: claimed` and save before any work.
- **Resolve**: append the answer under an `## Answer` heading, set `Status: resolved`, then append a context pointer (gist + link) to the map's Decisions-so-far in `map.md`.
