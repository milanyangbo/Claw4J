#!/bin/sh
# 摘要（Script Package 演示）。
#
# 这一版是**抽取式**的：挑出原文里最重要的几句，不改写。
# 不是不想做生成式，是做不了——脚本层的子进程只拿得到 PATH，
# 任何依赖 API key 的能力在这一层都跑不通（凭据传不进去，也不该传进去）。
# 生成式摘要要等它走 MCP 那一层，那边可以在起进程时显式给出环境变量。
#
# PYTHONIOENCODING 这一行不能省：环境里只有 PATH，没有 LANG / LC_ALL，
# 子进程落在 C locale 上。Python 3.7 以前没有 locale 兜底，stdout 就成了 ASCII，
# 打印中文直接 UnicodeEncodeError → 退出码 1 → stdout 被当失败丢掉，
# 使用者只看得到 stderr 上一句与此无关的 FutureWarning。写脚本包时照这一行抄。
PYTHONIOENCODING=utf-8 exec python3 "$(dirname "$0")/summarize.py" "$@"
