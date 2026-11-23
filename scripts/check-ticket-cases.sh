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
#
# 除了用例名，这一步还查两件"票面与总览对不对得上"的事（2026-11-20 / 11-23 加）：
#   - 新写的票必须有「TDD 用例清单」与 ADR 指针，标了 resolved 的还要一条「复核：<日期> <命令>」；
#   - 这次改到的 spec.md：票表要有「状态」列、issues/ 下的票一张不漏、状态与票面同一个词。

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

METHODS=$(mktemp)
CLASSES=$(mktemp)
REFS=$(mktemp)
SOURCES=$(mktemp)
SETTLED=$(mktemp)
trap 'rm -f "$METHODS" "$CLASSES" "$REFS" "$SOURCES" "$SETTLED"' EXIT

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
#
# "这张票是不是已收"先单独扫一遍（而不是在同一个 pass 里边读 Status 边判断）：Status 通常写在
# 票的末尾，而 TDD 表在它上面——按文件顺序判断会把这些行整批漏掉，闸照样绿（2026-11-20 实测：
# 新写的票表在前、Status 在后，✅ 行一条都没被提取，总数还是 153）。
find .scratch -path '*/issues/*.md' -print0 2>/dev/null \
    | xargs -0 grep -lE '^\*\*Status:\*\* *(resolved|done)' > "$SETTLED" 2>/dev/null || true

find .scratch -path '*/issues/*.md' -print0 2>/dev/null | xargs -0 awk '
    NR == FNR { settled[$0] = 1; next }
    FNR == 1 { on = (FILENAME in settled) }
    on && index($0, "✅") > 0 {
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
    }' "$SETTLED" > "$REFS"

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

# 4. spec 的票表要能一眼看出做完没有（2026-11-23 加）。
#
# 为什么：票面的 `**Status:**` 是事实，`spec.md` 那张总览表是给"现在还剩哪张"看的。两处各写一遍
# 就一定会漂——2026-11-23 的实际状态是六张票全 resolved，而总览表还停在五张、连状态列都没有，
# 看目录完全看不出做完没有。这一步把"漂"变成红。
#
# 边界与「新票才查」同口径：**只查已经入规的**（表里已有「状态」列）与**还没提交的** spec。
# 历史上那几十份没有状态列的 spec 不追——要求全仓合规就是几十处红，红完没人补。
spec_bad=0
for spec in .scratch/*/spec.md; do
    [ -f "$spec" ] || continue
    dir=$(dirname "$spec")
    ls "$dir"/issues/*.md >/dev/null 2>&1 || continue

    # 表头是第一个以 | 开头的行。用整行判「有状态列」，避免只匹配到正文里出现"状态"二字。
    has_status=0
    awk -F'|' '
        !header && /^\|/ {
            header = 1
            for (i = 1; i <= NF; i++) {
                cell = $i
                gsub(/^[[:space:]]+|[[:space:]]+$/, "", cell)
                if (cell == "状态") found = 1
            }
        }
        END { exit(found ? 0 : 1) }' "$spec" && has_status=1

    pending=0
    if git status --porcelain -uall -- "$spec" 2>/dev/null | grep -qE '^(A[A-Z]? |\?\?| M|M )'; then
        pending=1
    fi

    # 只查本次改到的 spec，与上面「新票才查」同一个边界。历史那几十份形状各异（有的第一列是
    # 编号、有的用文件名、有的分成好几张表），强加一套解析规则只会误伤它们；而"没改就不追"
    # 正好对上这次的漏法——漏是因为改票时没同步总览，那种改动本来就把 spec 带在身边。
    [ "$pending" -eq 1 ] || continue

    if [ "$has_status" -eq 0 ]; then
        echo "红：$spec 的票表没有「状态」列——总览上看不出哪张做完。"
        spec_bad=$((spec_bad + 1))
        continue
    fi

    # 表里列了编号，就得有那张票；issues/ 下的票，也都得在表里。
    for num in $(awk -F'|' '
            /^\|/ {
                cell = $2
                gsub(/[^0-9]/, "", cell)
                if (cell != "") print cell
            }' "$spec" | sort -u); do
        if ! ls "$dir/issues/${num}-"*.md >/dev/null 2>&1; then
            echo "红：$spec 的表里列了票 ${num}，但 $dir/issues 下没有 ${num}-*.md"
            spec_bad=$((spec_bad + 1))
        fi
    done

    for ticket in "$dir"/issues/*.md; do
        base=$(basename "$ticket")
        num=${base%%-*}
        want=$(awk '/^\*\*Status:\*\*/ { sub(/^\*\*Status:\*\*[[:space:]]*/, ""); print; exit }' "$ticket")
        row=$(awk -F'|' -v n="$num" '
                /^\|/ {
                    cell = $2
                    gsub(/[^0-9]/, "", cell)
                    if (cell == n) { print; exit }
                }' "$spec")
        if [ -z "$row" ]; then
            echo "红：$dir 里的票没进总览表（总览上看不见它做没做完）：$base"
            spec_bad=$((spec_bad + 1))
            continue
        fi
        got=$(printf '%s' "$row" | awk -F'|' '
                {
                    cell = $(NF - 1)
                    gsub(/^[[:space:]]+|[[:space:]]+$/, "", cell)
                    print cell
                }')
        if [ "${want:-<空>}" != "$got" ]; then
            echo "红：$base 的状态两处不一致：票面 ${want:-<空>} / 总览 ${got:-<空>}"
            spec_bad=$((spec_bad + 1))
        fi
    done
done

if [ "$spec_bad" -ne 0 ]; then
    echo
    echo "总览表（spec.md）要能直接回答「哪几张完了、还剩哪张」（AGENTS.md「ADR → 票 → 代码」那节）："
    echo "  1. 票表加「状态」列，取值与票面的 \`**Status:**\` 是同一个词（resolved / in progress / open）；"
    echo "  2. issues/ 下的票一张都不能漏列——漏列等于从总览上消失；"
    echo "  3. 收票时两处一起改，别只改一处。"
    exit 1
fi


# 引用数为 0 只有一种解释：提取规则没命中（票面换了写法），不是"都通过"。
# 这种闸最坏的状态是永远绿，所以说出来。
if [ "$total" -eq 0 ]; then
    echo "注意：一条引用都没提出来。多半是票面写法变了（用例名没放进反引号），这一步现在什么也没验。"
fi

# 5. 新增的票必须交代两件事：裁决在哪（ADR）、以及「TDD 用例清单」（2026-11-20 起）。历史票不追。
#
# 为什么只看新增：仓库里 126 张 resolved 的票有 101 张没有这张表、也没有 ADR 指针
# （07 之前是"验收"小节，不是这三列）。要求全仓合规就是 101 张红，红完没人补。
# 用户 2026-11-20 裁决："缺 ADR 和票里没 TDD 表，历史就不管了，新的必须有 tdd"。
#
# 判据是 git 里的"还没进 HEAD"：未跟踪 `??` 或已暂存的 `A`。所以**这道闸在提交前跑才有意义**，
# 提交之后就查不到了——与"另一个人 clone 下来"的那种漂移无关，这里只管"我这次新写的票"。
#
# ADR 那一半认三种写法，写不出就说明还没想：`docs/adr/00xx`（链接或路径）、`ADR-00xx`（编号），
# 或 `无需 ADR：<理由>`（改文案、挑实现这类自己定的事，写清为什么不需要）。
NEW_TICKETS=$(git status --porcelain -uall -- .scratch 2>/dev/null \
    | grep -E '^(A[A-Z]? |\?\?)' \
    | cut -c4- \
    | grep -E '(^|/)issues/[^/]+\.md$')

ticket_missing=0
if [ -n "$NEW_TICKETS" ]; then
    while IFS= read -r ticket; do
        [ -f "$ticket" ] || continue
        if ! grep -qE '(docs/adr/00[0-9]{2}|ADR-00[0-9]{2}|无需 ADR)' "$ticket"; then
            echo "红：新增的票没交代裁决在哪（写 docs/adr/00xx 或 ADR-00xx；确实不需要就写「无需 ADR：<理由>」）：$ticket"
            ticket_missing=$((ticket_missing + 1))
        fi
        if ! grep -q '^## TDD 用例清单' "$ticket"; then
            echo "红：新增的票没有「TDD 用例清单」一节：$ticket"
            ticket_missing=$((ticket_missing + 1))
            continue
        fi
        # resolved 的票还要求一条复核记录（"只写 ✅ 不写依据也算没验证"）。
        if grep -q '^\*\*Status:\*\* *\(resolved\|done\)' "$ticket" \
            && ! grep -q '复核：' "$ticket"; then
            echo "红：新增的票标了 resolved，却没有「复核：<日期> <命令>」记录：$ticket"
            ticket_missing=$((ticket_missing + 1))
        fi
    done <<< "$NEW_TICKETS"
fi

if [ "$ticket_missing" -ne 0 ]; then
    echo
    echo "新票要交代两件事，都是动代码之前就该有的（AGENTS.md「ADR → 票 → 代码，顺序不能换」）："
    echo "  1. 裁决在哪：docs/adr/00xx 或 ADR-00xx；确实不需要就写「无需 ADR：<理由>」。"
    echo "  2. 「TDD 用例清单」：判什么行为 + 用例名（放反引号里）+ 先红的样子，收票时标 ✅ / ❌ / ⏳，"
    echo "     下面补一行「复核：<日期> <命令>」。写不出「先红的样子」，说明还没想清要改什么。"
    exit 1
fi
