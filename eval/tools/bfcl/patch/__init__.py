"""给 BFCL 打的最小本地补丁（票 17）。

## 为什么需要它

`bfcl-eval==2026.3.23` 的 `OpenAICompletionsHandler.__init__` 在构造时**无条件**建 client：

    self.client = OpenAI(**self._build_client_kwargs())

而 `_build_client_kwargs()` 只认 `OPENAI_API_KEY` / `OPENAI_BASE_URL` 这几个变量。
`DeepSeekAPIHandler.__init__` 是**先**调 `super().__init__()`、**再**把自己的 client 换成
`OpenAI(base_url="https://api.deepseek.com", api_key=DEEPSEEK_API_KEY)` —— 于是父类那一步
在没有 `OPENAI_API_KEY` 的机器上直接抛 `OpenAIError: Missing credentials`，
永远走不到后面那行覆写。

也就是说：**BFCL 注册了 DeepSeek，但在没有 OpenAI key 的环境里跑不起来**。这是上游的 bug，
不是我们的配置问题（`bfcl models` 能列出 `DeepSeek-V3.2-Exp-FC`，`supported_models.py` 里也有）。

## 补的是什么

只补这一个洞：父类构造时缺 `api_key`，且**确实是 DeepSeek 这条注册项**，就用
`DEEPSEEK_API_KEY` 顶上。补完的 client 随后仍会被 `DeepSeekAPIHandler` 换成它自己那个，
所以这个值只用于"让构造过程不炸"。

不选另外两条路的理由：

- 不改第三方文件（`site-packages` 里改一行，重装就没了，而且没人看得出改过）。
- 不设 `OPENAI_API_KEY=$DEEPSEEK_API_KEY` 糊过去：那会让"用 OpenAI handler 的注册项"也拿到
  一把 DeepSeek 的 key，错得安静。

## 代价

补丁是运行时 monkeypatch，**升级 `bfcl-eval` 后要先看一眼本文件是否还需要**。
判据：`python eval/tools/bfcl/boot.py generate --model DeepSeek-V3.2-Exp-FC ...` 直接能跑，
就说明上游修了，本文件可以删。`boot.py` 会在补丁未生效（目标函数签名变了）时打印警告，
不会静默地"看起来跑通了"。
"""

from __future__ import annotations

import os
import sys

_PATCHED = False


def apply() -> bool:
    """装上补丁。返回 True 表示这次真的打了（重复调用只打一次）。"""
    global _PATCHED
    if _PATCHED:
        return True

    try:
        from bfcl_eval.model_handler.api_inference.openai_completion import (
            OpenAICompletionsHandler,
        )
    except Exception as exc:  # 上游改了模块路径
        print(f"[patch] 跳过：找不到 OpenAICompletionsHandler（{exc}）", file=sys.stderr)
        return False

    original = getattr(OpenAICompletionsHandler, "_build_client_kwargs", None)
    if original is None:
        print(
            "[patch] 跳过：_build_client_kwargs 不在了，上游可能已重写构造逻辑。"
            "请先确认是否还需要本补丁。",
            file=sys.stderr,
        )
        return False

    def _build_client_kwargs(self):  # noqa: ANN001 - 与上游同签名
        kwargs = original(self)
        if "api_key" in kwargs:
            return kwargs
        if "DeepSeek" in (getattr(self, "registry_name", "") or ""):
            api_key = os.getenv("DEEPSEEK_API_KEY")
            if api_key:
                kwargs["api_key"] = api_key
        return kwargs

    OpenAICompletionsHandler._build_client_kwargs = _build_client_kwargs
    _PATCHED = True
    return True
