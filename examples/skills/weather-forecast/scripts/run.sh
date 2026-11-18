#!/bin/sh
# 天气预报（技能里的脚本演示）。
#
# 脚本只做一件事：把参数交给同目录里的 Python 文件。
# 为什么要绕一层 shell 而不是让模型直接跑 .py：解释器在哪台机器上是 PATH 的事，
# 用 `python3` 而不是写死 `/usr/bin/python3` 就是为了把这件事留给环境。
#
# 参数是模型写的一条命令里的一段（`bash …/scripts/run.sh 深圳`），
# 到这一层就是一个普通字符串：这里不解析它，也不把它拼进另一条命令。
set -e
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$here/weather.py" "$@"
