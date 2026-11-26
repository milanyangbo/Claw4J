#!/usr/bin/env python3
"""BFCL 的启动引导（票 17）。

用它代替 `bfcl` 命令，只做三件事，都是这个环境或上游强加给我们的：

1. **给 tempfile 一个确定的落脚点。** 本机的 python 进程里 `tempfile.gettempdir()` 探测
   必然失败（沙箱对随机命名的新建路径不放行），症状是每行命令前都刷一句
   `Error in sitecustomize`，装包、跑脚本全挂。直接给 `tempfile.tempdir` 赋值就绕开了探测。
2. **装本地补丁**（见 `patch/__init__.py`）：上游的 DeepSeek handler 构造时会先炸一次。
3. **把 BFCL 的根目录钉到本目录**，于是它读的 `test_case_ids_to_generate.json`
   和写的 `result/` 都落在 `eval/tools/bfcl/` 下，不污染 `site-packages`。

用法与 `bfcl` 完全一致：

    python eval/tools/bfcl/boot.py models
    python eval/tools/bfcl/boot.py generate --model DeepSeek-V3.2-Exp-FC --test-category simple_python --run-ids

一般不直接调它，走 `./eval/tools/bfcl/run.sh`。
"""

from __future__ import annotations

import importlib.util
import os
import sys
import tempfile

_HERE = os.path.dirname(os.path.abspath(__file__))


def _prepare_tempdir() -> None:
    """把一个真实存在的目录交给 tempfile，跳过它自己的可用性探测。

    目录本身由 `run.sh` 先用 `/bin/mkdir` 建好——这里的 `os.makedirs` 已被沙箱的
    broker 包过，建新目录可能被拒绝，不该在启动阶段冒险。
    """
    candidate = os.path.join(_HERE, "tmpdir")
    if os.path.isdir(candidate):
        tempfile.tempdir = candidate
        os.environ["TMPDIR"] = candidate
    else:
        print(
            f"[boot] 缺临时目录 {candidate}：先跑 ./eval/tools/bfcl/run.sh（它会建）",
            file=sys.stderr,
        )


def _apply_local_patch() -> None:
    spec = importlib.util.spec_from_file_location(
        "bfcl_local_patch", os.path.join(_HERE, "patch", "__init__.py")
    )
    if spec is None or spec.loader is None:
        print("[boot] 跳过补丁：patch/__init__.py 读不到", file=sys.stderr)
        return
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.apply()


def main() -> None:
    _prepare_tempdir()
    os.environ.setdefault("BFCL_PROJECT_ROOT", _HERE)
    _apply_local_patch()

    from bfcl_eval.__main__ import cli

    sys.argv = ["bfcl"] + sys.argv[1:]
    cli()


if __name__ == "__main__":
    main()
