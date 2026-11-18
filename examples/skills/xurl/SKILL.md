---
name: xurl
description: 用本机的 xurl CLI 访问 X（Twitter）：看一条推文、搜最近的话题。用户给出推文链接或要搜 X 上的内容时用它；本机没装 xurl 时它会告诉你怎么装。
---

# 用 xurl 访问 X

## 什么时候用

用户给了一个 `x.com` / `twitter.com` 的链接，或者要"搜一下 X 上大家怎么说"。
**这一层不实现 X 的客户端**：它只是把参数交给本机的 `xurl` CLI，把输出带回来。
所以 `xurl` 能不能用、有没有认证过，取决于这台机器，不取决于这段正文。

## 怎么跑

脚本在技能目录的 `scripts/` 下，目录路径是工具返回的第一行（`Base directory for this skill:`）。
用 `Bash` 照抄这一条（`{baseDir}` 会被替换成那个目录）：

```
bash {baseDir}/scripts/run.sh tweet get 1234567890
```

`run.sh` 后面的东西**原样**交给 `xurl`：子命令与参数照 `xurl --help` 的写法给。
参数里有空格或 `&` 时要加引号，这层不会替你分词。

## 期望的输出

`xurl` 的 stdout 原样返回（JSON 或纯文本，取决于子命令）。退出码非 0 时返回的是 `xurl` 的 stderr。

两种"没出数"要分清：

- `未安装 xurl CLI。安装：brew install …` → 环境缺东西。把那一句转告用户，别重试。
- 一串 `401` / `Unauthorized` → 装了但没认证。让用户跑 `xurl auth login`。

## 不要做的事

- 不要在没装 xurl 的机器上换一个"用 HTTP 直连 X API"的写法：那需要凭据，这一层拿不到。
- 不要把 `xurl` 的输出改写成"我查到的"——贴原文，必要时再解释。
- 不要执行写操作（发推、删推）而用户没明确要求：`xurl` 子命令里带 `create` / `delete` 的都算。
