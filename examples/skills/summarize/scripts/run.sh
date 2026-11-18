#!/bin/sh
# 摘要（技能里的脚本演示）。
#
# 技能本身不被执行（OpenClaw 的形态：skills_read 只读正文，脚本由模型用 exec / Bash 跑）。
# 所以这个文件是**普通文件**，不是"入口"：它的路径写在 SKILL.md 里，由模型拼出来调用。
#
# 参数给相对路径时按**技能目录**解析，不是按调用者的当前目录：
# 先切到技能目录再交给 python，`bash <技能目录>/scripts/run.sh sample.md` 在哪儿跑都一样。
#
# 这一版是**抽取式**的：挑出原文里最重要的几句，不改写。
# 不是不想做生成式，是做不了——Bash 的子进程只拿得到声明的那几个环境变量（默认只有 PATH），
# 依赖 API key 的能力在这里跑不通（凭据传不进去，也不该传进去）。生成式摘要走 MCP 那一层。
#
# PYTHONIOENCODING 这一行不能省：环境里只有 PATH，没有 LANG / LC_ALL，
# 子进程落在 C locale 上。Python 3.7 以前没有 locale 兜底，stdout 就成了 ASCII，
# 打印中文直接 UnicodeEncodeError → 退出码 1 → 输出被当失败丢掉，
# 使用者只看得到 stderr 上一句与此无关的 FutureWarning。写这类脚本时照这一行抄。
set -e
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$here/.."
exec env PYTHONIOENCODING=utf-8 python3 "$here/summarize.py" "$@"
