#!/usr/bin/env bash
#
# 收票闸：**票面里打 ✅ 的用例名，必须在仓库里真的存在**。
#
# 为什么要有这一步：`AGENTS.md` 写着「`Status: resolved` 的门槛是表里没有 ❌」，
# 但没有任何东西会去核对"表里那行写的东西存在不存在"。2026-11-18 的复盘里五处返工
# 是同一个形状——resolved 的票面点着六条全仓都不存在的用例名。删除词闸查的是
# "删掉的词还在不在"，查不到用例名；这一条补的就是那一半。
#
# 只查 `Status: resolved` / `done` 的票：还没收的票点名一条"待写"的用例是正常的。
# 只查打了 ✅ 的行：`⏳` 是"这一行不归本票"，`❌` 本来就该让收票失败，不归这一步管。
#
# 认三种写法（都要放在反引号里，否则这一步看不见它）：
#   `XxxTest#methodName`   类 + 方法，最常见
#   `#methodName`          只有方法名，在本仓所有测试类里找
#   `XxxTest`              只有类名
#
# `Class#name` 只在**类名以 Test 结尾**时才算用例引用：票面里也会写
# `Pending#allowAlways` 这种指向生产代码 record 访问器的东西，它不是用例，不该被判红。
# 代价是类名打错成 `SandboxTset#…` 会被放过——这条闸管"承诺的用例在不在"，不管拼写。
#
# 用法：./scripts/check-ticket-cases.sh
# 退出码：0 = 全部对得上；非 0 = 有引用找不到（会把每一条打出来）。

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

METHODS=$(mktemp)
CLASSES=$(mktemp)
REFS=$(mktemp)
SOURCES=$(mktemp)
trap 'rm -f "$METHODS" "$CLASSES" "$REFS" "$SOURCES"' EXIT

# 0. 先落一份文件清单。空清单必须当场判红：`xargs` 在没输入时仍会跑一次命令，
#    那时 awk 会去读 stdin 然后挂住——一道闸最坏的形态是"卡住"或"永远绿"。
find claw4j-*/src/test/java -name '*.java' -print0 > "$SOURCES" 2>/dev/null
if [ ! -s "$SOURCES" ]; then
    echo "红：一个测试源文件都没找到，这一步没法核（find claw4j-*/src/test/java 空）。"
    exit 1
fi

# 1. 仓库里真实存在的测试方法：Class#method（每个 `void xxx(` 一行）。
xargs -0 awk '
    {
        cls = FILENAME
        sub(/^.*\//, "", cls)
        sub(/\.java$/, "", cls)
        rest = $0
        while (match(rest, /void[ \t]+[A-Za-z0-9_]+[ \t]*\(/)) {
            name = substr(rest, RSTART, RLENGTH)
            sub(/^void[ \t]+/, "", name)
            sub(/[ \t]*\($/, "", name)
            print cls "#" name
            rest = substr(rest, RSTART + RLENGTH)
        }
    }' < "$SOURCES" > "$METHODS"

# 2. 真实存在的测试类名（每个文件一行）。
xargs -0 awk '
    FNR == 1 {
        cls = FILENAME
        sub(/^.*\//, "", cls)
        sub(/\.java$/, "", cls)
        print cls
    }' < "$SOURCES" > "$CLASSES"

# 3. 票面里声明存在的东西：resolved / done 的票、打了 ✅ 的行、反引号里的那种写法。
find .scratch -path '*/issues/*.md' -print0 2>/dev/null | xargs -0 awk '
    FNR == 1 { settled = 0 }
    /^\*\*Status:\*\*/ {
        if (index($0, "resolved") > 0 || index($0, "done") > 0) settled = 1
    }
    settled && index($0, "✅") > 0 {
        rest = $0
        while (match(rest, /`[^`]+`/)) {
            tok = substr(rest, RSTART + 1, RLENGTH - 2)
            rest = substr(rest, RSTART + RLENGTH)
            if (tok ~ /^[A-Za-z_][A-Za-z0-9_]*Test#[A-Za-z0-9_]+$/ \
                || tok ~ /^#[A-Za-z0-9_]+$/ \
                || tok ~ /^[A-Za-z_][A-Za-z0-9_]*Test$/) {
                print FILENAME "\t" tok
            }
        }
    }' > "$REFS"

total=0
missing=0
report=""

while IFS=$'\t' read -r file token; do
    [ -z "${token:-}" ] && continue
    total=$((total + 1))
    case "$token" in
        \#*)
            # `#method`：后面接的是方法名，类不限。
            if ! awk -v suffix="$token" '
                    substr($0, length($0) - length(suffix) + 1) == suffix { found = 1 }
                    END { exit(found ? 0 : 1) }' "$METHODS"; then
                missing=$((missing + 1))
                report="${report}${file}  ->  ${token}
"
            fi
            ;;
        *\#*)
            # `Class#method`：整条精确匹配。
            if ! grep -Fxq -- "$token" "$METHODS"; then
                missing=$((missing + 1))
                report="${report}${file}  ->  ${token}
"
            fi
            ;;
        *)
            if ! grep -Fxq -- "$token" "$CLASSES"; then
                missing=$((missing + 1))
                report="${report}${file}  ->  ${token}
"
            fi
            ;;
    esac
done < "$REFS"

if [ "$missing" -ne 0 ]; then
    printf '%s' "$report"
    echo
    echo "红：上面这些是收票时打了 ✅、仓库里却没有的用例名（共 ${missing} 条，查了 ${total} 条引用）。"
    echo "两种改法，选一种，别删行了事："
    echo "  - 用例还在、只是改了名 → 把票面的名字改成真的那个；"
    echo "  - 这一条压根没有对象（随某次删除失去被断言的东西）→ 标成 ⏳ 搬进「缺口」，写清去向。"
    exit 1
fi

echo "票面引用 ${total} 条，全部对得上仓库里的测试。"

# 引用数为 0 只有一种解释：提取规则没命中（票面换了写法），不是"都通过"。
# 这种闸最坏的状态是永远绿，所以说出来。
if [ "$total" -eq 0 ]; then
    echo "注意：一条引用都没提出来。多半是票面写法变了（用例名没放进反引号），这一步现在什么也没验。"
fi
