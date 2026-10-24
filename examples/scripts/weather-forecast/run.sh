#!/bin/sh
# 天气预报（Script Package 演示）。
#
# 入口只做一件事：把参数交给同目录里的 Python 脚本。
# 为什么要绕一层 shell 而不是直接把 .py 当入口：包清单里的 entrypoint 是
# "这个包怎么被启动"，而 Python 解释器在哪台机器上是 PATH 的事——
# 用 `python3` 而不是写死 `/usr/bin/python3` 就是为了让这件事留给环境。
#
# 参数已经由 ScriptPackageRunner 做过路径校验与引号转义（ADR-0002），
# 这里不再解析它，也不把它拼进任何一条命令。
exec python3 "$(dirname "$0")/weather.py" "$@"
