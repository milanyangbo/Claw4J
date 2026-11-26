# 票 26：把工具目录从 24 个扩到 50 个——扩的不是"程序周边"，是生活、娱乐、艺术、消费

**Status:** resolved（2026-11-27 00:30 落地，见文末「复核」）
**裁决来源：** 用户 2026-11-26「tool 和 mcp 还是有点少，可以多增加一些吧，至少增加一倍多」
「github / mail / git / sales 新增同质化有点严重」「可以找一些其他方向的」
「这些也有点同质化，都是跟程序有关的，有不同场景的吗？比如娱乐啊，艺术等等」。
**no ADR needed**（沿用 ADR-0003 的能力三层；新工具都走已有的 MCP stdio 骨架）。

## 被否掉的两版，以及原因

| 版本 | 内容 | 为什么不行 |
| --- | --- | --- |
| v1 | github 3→11、mail 2→8、新 git 8、sales 1→6 | 同族工具描述只差几个词。而 24 个工具的向量分本来就挤在 0.29–0.83、相关与不相关完全重叠（票 25 的 `scores.jsonl`）。再塞近义词，分数会掉，但掉的原因是"我故意出了近义题" |
| v2 | 时间 / 网络 / 压缩 / 表格 / 文本 / 主机 | 六个域全是**程序员工具箱**。工具全都是 `date`、`dig`、`unzip`、`sqlite3`、`sha256sum` 的包装，检索只要有"技术词汇"就够，考不到"用户到底想干什么" |

## 这一版的取舍：全部是程序之外的生活域

一个判据：**如果一个工具可以用 `Bash` 一行命令替代，它价值就低**。上一版 26 个里 20 个都能
（`dig` / `unzip` / `sqlite3` / `sha256sum` / `date`）。这一版全部是**外部数据源**，`Bash` 替不了
（要拼 API、解析 JSON、处理鉴权），必须有一个专门的工具。

## 选谁：先实测，再决定

拿 18 个公开 API 逐个探过（`urllib`，8–25 秒超时，串行 + 间隔），**只有 10 个在这台机器上真能通**：

| 通 | 延迟 | 不通 | 症状 |
| --- | --- | --- | --- |
| open-meteo（天气 + 地理编码 + 空气质量） | 1.1s | Wikipedia REST | 超时 |
| TVmaze（剧集） | 0.9s | Nominatim（地点） | 超时 |
| iTunes Search（音乐 / 专辑 / 歌手 / 播客 / 有声书 / 电子书） | 0.4s | Met Museum | **410 Gone**（接口已下线） |
| TheMealDB（菜谱） | 1.4s | Art Institute of Chicago | 403（带 `AIC-User-Agent` 仍 403） |
| Open Food Facts（营养） | 1.8s | jokeapi v2 / zenquotes / quotable | SSL 断连 |
| open.er-api.com（汇率） | 2.5s | coingecko / OSRM / openalex | SSL 断连 |
| TheColorAPI（配色） | 3.1s | **iTunes 电影 / 剧集** | 0 条（`entity=movie`/`tvShow` 已下架，带 `country=US` 也是 0） |
| Cleveland Museum of Art | 1.7s | **Google Books** | 502 |
| icanhazdadjoke（笑话） | 0.8s | **Gutendex** | 超时 |
| dictionaryapi.dev（词典） | 2.3s | **Open Library** | 不稳定（一次 10.5s 通，一次 502） |

全部免费、无需 key。**没通的一律不做**——写一个调不通的工具，等于往目录里塞一个假能力。

两条改道是实测逼出来的：

- **影视改用 TVmaze**。iTunes 的 `entity=movie` 与 `entity=tvShow` 都返回 0 条，Apple 已经不提供影视搜索了。
  TVmaze 免费、无 key、能搜剧集与详情，但**只有剧集，没有电影**——所以这一版没有"电影"工具。
- **不做书**。Open Library 时通时不通（同一台机器 10 分钟内测到 10.5s 成功与 502 各一次），
  Google Books 502、Gutendex 超时。书是"想加但通不了"，不是"没想到"。

## 目标：24 → 50（+26），六个 server

只有标准库（本机 `pypdf` / `PIL` / `openpyxl` 都没装），走现有 `mcp_stdio.Tool` 骨架。

### `weather_mcp_server.py`（3）— 天气

| 工具 | 一句话 |
| --- | --- |
| `weather_now` | 某个城市现在的天气（温度、体感、湿度、风） |
| `weather_forecast` | 未来 7 天的天气与温度区间 |
| `air_quality` | 某地的空气质量（PM2.5 / PM10 / AQI） |

### `media_mcp_server.py`（6）— 剧集、音乐、播客、有声书

| 工具 | 一句话 |
| --- | --- |
| `tv_show_search` | 按剧名搜剧集（首播年份、类型、评分） |
| `tv_show_detail` | 某部剧的详情（播出平台、集数、简介） |
| `music_search` | 搜歌曲（歌手、专辑、时长） |
| `artist_lookup` | 按歌手名看他/她的专辑 |
| `podcast_search` | 按关键词搜播客节目 |
| `audiobook_search` | 按书名或作者搜有声书 |

### `food_mcp_server.py`（5）— 做饭、营养

| 工具 | 一句话 |
| --- | --- |
| `recipe_search` | 按菜名搜菜谱 |
| `recipe_detail` | 某个菜谱的配料表与步骤 |
| `recipe_by_ingredient` | 冰箱里有什么，能做什么菜 |
| `food_barcode` | 按条形码查食品的营养成分 |
| `food_search` | 按名字搜食品的热量、糖、脂肪 |

### `culture_mcp_server.py`（5）— 艺术、娱乐、语言

| 工具 | 一句话 |
| --- | --- |
| `art_search` | 按关键词搜艺术品（Cleveland 艺术馆） |
| `art_detail` | 作品详情：作者、年代、材质、图片 |
| `joke_random` | 来一个随机英文冷笑话 |
| `joke_search` | 按关键词找相关的冷笑话 |
| `word_define` | 英文单词的释义、词性、音标 |

### `life_mcp_server.py`（4）— 钱与颜色

| 工具 | 一句话 |
| --- | --- |
| `fx_convert` | 两种货币之间换算（按最新汇率） |
| `fx_rates` | 某货币对一篮子主要货币的汇率 |
| `color_info` | 某个颜色的名称、RGB、HSL |
| `color_scheme` | 给一个主色，生成一套配色方案 |

### `health_mcp_server.py`（3）— 身体（纯计算，不联网）

| 工具 | 一句话 |
| --- | --- |
| `bmi_calc` | 算 BMI 并给出体型分类 |
| `calorie_needs` | 算基础代谢与每日热量需求（按活动量） |
| `water_intake` | 算每日建议饮水量 |

合计 3+6+5+5+4+3 = **26**，24 + 26 = **50**（2.08 倍）。

## 这一版对检索的意义

24 个旧工具里，一半以上共享"代码/文件/终端"这套词汇。新增的 26 个与它们**没有任何共同的能力词**：
"票房"、"配料"、"汇率"、"配色"、"卡路里"、"馆藏"。所以：

- 检索要判的不再是"哪个技术词撞上了"，而是"这句话属于哪个场景"。
- `R@5` 掉了，说明我们的检索是字面匹配；没掉，说明它真的在按语义找。**这比分数本身更有信息量。**

## 必须同步做：题库 50 → 80

现在 50 题只覆盖 22 个工具（`Task`、`github_api` 一道题都没有）。新增 26 个却不扩题，
它们在评测里等于不存在——catalogue 变长、`R@5` 分母不变，分数还会因为候选变多而虚降。

做法：每个新域 4–5 题，覆盖该域每个工具，中文问法、**不点工具名、不写 API 名**。
`ToolGoldTest` 加一条硬断言：catalogue 里每个工具都至少有一道题——写完这条现在就会红。

## 先说清楚风险

- **依赖外部 API**：限流、下线（Met Museum 就是 410 走的）、变慢（Open Library 10.5s）。
  这些工具**不影响检索分数**——检索只读 catalogue 的名字与描述，不发起调用；但"它真能干活"
  这件事依赖对方还在。
- **`R@5` 会掉，掉多少不知道**。候选池 24 → 50。跌破 0.80 就如实报红，不改门。
- **不做**：百科（Wikipedia 不通）、地点与路线（Nominatim 超时、OSRM 断连）、加密行情
  （coingecko 断连）。这些是"想加但通不了"，不是"没想到"。

## TDD 用例清单

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 六个 server 的工具名唯一、描述非空、required 都在 properties 里、`tools/list` 能列出 | 新建 `examples/mcp/test_servers.py`（`unittest`，零依赖，起子进程走 JSON-RPC） | 没有这个文件；名字打错（`recipe_detial`）没有任何东西会红 |
| 纯计算那三个真算得对（不依赖网络） | 同上：`bmi_calc(175, 80)` 必须是 26.1，`water_intake(70)` 必须在合理区间 | 没有用例 |
| 需要出网的工具在断网时返回原因而不是抛栈 | 同上：把 base URL 指向一个不存在的域名 | 没处理就是 `URLError` 冒到 MCP 层 |
| 目录里每个工具都有题 | `ToolGoldTest#everyCataloguedToolHasAQuestion`（新增） | `Task`、`github_api` 无题，一写就红 |
| 新工具真的进了 catalogue | `ToolRetrievalExportTest` 的 required 集合（338 行） | 现在只钉了 4 个名字 |

复核：`./scripts/verify.sh` 全绿；`./eval/tools/retrieval/run.sh` 出新 catalogue 与 `R@5`。

## 缺口

- **`Open Library` 要 10.5 秒**。`book_search` 会明显慢于别的工具；如果 MCP 的
  `request-timeout`（默认 30s）被它拖爆，就把超时收到 8 秒并如实返回"对方太慢"。
- **`health` 三个是纯计算**，与外部数据无关。它们存在的意义是把"生活域"补完整，
  不是为了凑数——BMI / 热量 / 饮水是真实助理会被问到的三类问题。
- **不加 `Write` / `Edit`**：它们在 `pinned` 之外、默认档不开。开它们改的是能力面。

## 复核（2026-11-27 00:30）

工具目录 **24 → 50**，题库 **50 → 80**，两个提交：`27dcde11`（server 与配置）、`3930110c`（题库与基线）。

**26 个工具全部真调过一次**（不是只列个名字）：

```
weather_now 北京 → 9.2°C 体感 4.2°C 晴间多云
air_quality 北京 → PM2.5 224 μg/m³，AQI 134 极差
tv_show_search 绝命毒师 → #169，9.2 分，Drama/Crime/Thriller
tv_show_detail 169 → AMC，主演 Bryan Cranston、Aaron Paul…
music_search billie eilish → ocean eyes / WILDFLOWER
recipe_detail 52777 → 9 样配料带克数 + 步骤
food_barcode 3017620422003 → Nutella，营养分级 E，539 kcal/100g
art_search monet → 18 件，The Red Kerchief / Water Lilies
joke_random → "Don't buy flowers at a monastery…"
word_define serendipity → 音标 + noun + 两条释义
fx_convert 100 USD → 668.32 CNY（注明"每天更新一次，不是实时行情"）
color_info #24B1E0 → Cerulean，rgb(36,177,224)，hsl(195,75%,51%)
bmi_calc 175/80 → 26.1 超重（中国标准 WS/T 428-2013）
```

**评测结果**（`./eval/tools/retrieval/run.sh`，退出码 0）：

```
分类          lucene  hybrid-0.5  hybrid-0.0
base            0.600       0.400       0.500
business        0.682       0.818       0.864
zh-en           0.650       0.800       0.850
life            0.821       1.000       1.000     ← 30/30
总 R@5                    0.825       0.863
```

`life` 一个没漏，是这一版要回答的那个问题的答案：**它是按场景找的，不是只会撞技术词。**
掉的是 `base`（10 题里 4 题）——这一档票 18 就写明"一半刻意写成纯语义、当时命中不了"，
现在是全表最弱，要不要查是下一件事。

### 两个坑

1. **评测上下文不读 `application-local.yml`**。`ToolRetrievalExportTest` 用的是独立的
   `@SpringBootConfiguration` + `@EnableAutoConfiguration`，MCP server 是在
   `properties(...)` 里硬编码的。第一次跑直接报"60 秒内 MCP 工具没到位：[fx_convert,
   bmi_calc, tv_show_search, recipe_search, weather_now, art_search]"，而配置里明明加了。
   现在两处都有，注释里互相指认——但**这是结构问题**：加一个 server 要改两个地方，
   忘一处不会编译失败，只会在 60 秒后报一句看起来像环境问题的话。
2. **TVmaze 的 cast 是 `[{"person": {"name": …}}]`**，我第一版写成 `cast[i]["name"]`，
   于是"主演"永远是 `—`，**不报错、只是数据丢了**（中间还撞过一次 `None` 进 `join` 抛栈）。
   两个都是靠"真调一次、肉眼读输出"发现的，契约测试查不出来。

### 门禁

`./scripts/verify.sh` 这一次**没有全绿**，两个红都与本票改动无关：

- `ChatPipelineTest#realModelTurnsNaturalLanguageIntoTheRightToolCall`：
  **DeepSeek 返回 402 Insufficient Balance**（`curl` 复核过，余额确实耗尽）。
- `SlackFlowTest#aSlackMentionIsAnsweredThroughTheCallback`：应用配了 qwen 兜底，
  DeepSeek 402 之后降级到 `qwen-plus`（实测 200 通），而这条断言要求回答里出现库里的
  `128400`——`ApplicationAssemblyTest` 的注释自己写着"这类断言守的其实是模型这一轮愿不愿意
  调工具，换个模型就会红"。同一条断言在 `ApplicationAssemblyTest` 里是绿的，对照成立。

排除那一条真模型用例之后：`claw4j-agent` 230 绿、`claw4j-eval` 与 `claw4j-app` 147 里 146 绿。
**要真绿，得先给 DeepSeek 充值**——这不是代码能修的。
