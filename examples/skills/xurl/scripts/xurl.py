#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 xurl CLI 包成一个脚本包。

参数是 argv 数组，不经 shell——与 Java 侧那条规则是同一条约束（ADR-0002）。
模型给出的输入到这里已经是"一个字段值"，不会成为命令行的一部分。
"""

import subprocess
import sys

TIMEOUT = 25


def main():
    args = sys.argv[1:]
    if not args:
        print("用法：xurl <xurl 的子命令与参数>，例如：xurl tweet get 1234567890")
        return 2
    try:
        completed = subprocess.run(["xurl"] + args, capture_output=True, text=True, timeout=TIMEOUT)
    except FileNotFoundError:
        print("未安装 xurl CLI。安装：brew install xdevplatform/tap/xurl，然后 xurl auth login。")
        return 1
    except subprocess.TimeoutExpired:
        print("xurl 超过 %d 秒没返回，已放弃。缩小范围再试一次。" % TIMEOUT)
        return 1

    output = (completed.stdout or "").strip()
    if completed.returncode != 0:
        print((completed.stderr or "").strip() or output or "xurl 失败且没有输出")
        return completed.returncode
    print(output or "（xurl 没有输出）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
