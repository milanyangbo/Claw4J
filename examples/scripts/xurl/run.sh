#!/bin/sh
# 包 xurl CLI（Script Package 演示）。
#
# 这一层不认识 xurl，也不自己实现它的能力：脚本本体只做"把参数交给 CLI、
# 把输出带回来、缺 CLI 时给一句怎么装"。这就是 ADR-0003 里"包外部 CLI"的含义——
# 用 Java 重写一个 X 客户端的成本远高于收益，而这里只有二十行。
exec python3 "$(dirname "$0")/xurl.py" "$@"
