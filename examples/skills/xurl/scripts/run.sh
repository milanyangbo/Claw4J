#!/bin/sh
# 包 xurl CLI（技能里的脚本演示）。
#
# 这里不认识 xurl，也不自己实现它的能力：脚本本体只做"把参数交给 CLI、
# 把输出带回来、缺 CLI 时给一句怎么装"。用 Java 重写一个 X 客户端的成本远高于收益，
# 而这里只有二十行。
set -e
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$here/xurl.py" "$@"
