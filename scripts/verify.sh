#!/usr/bin/env bash
#
# 一条命令跑完全部单元测试，并守住三个数：
#   1. 用例全过          —— 有失败、有错误就红
#   2. 跳过数必须为 0    —— 绿的但没验，比失败更危险
#   3. 行覆盖率 ≥ 60%    —— 父 POM 的 JaCoCo check 绑在 verify 上，破线构建直接失败
#
# 另外打印每模块的文件数与最大方法数（上限 10 / 20），超了判红。
#
# 用法：./scripts/verify.sh
# 退出码：0 = 全绿；非 0 = 上面任一条不成立。

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

# 真模型要 key（ADR-0011：没 key 是启动失败，不是跳过）。有 .env 就自动载入。
if [ -f .env ]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
fi

MODULES="claw4j-domain claw4j-channel claw4j-capability claw4j-agent claw4j-app"
MAX_FILES=10
MAX_METHODS=20
MIN_COVERAGE=60

echo "== 1/3 构建 + 测试 + 覆盖率闸（./mvnw -o clean verify）=="
./mvnw -o clean verify
status=$?
if [ "$status" -ne 0 ]; then
    echo
    echo "构建失败：看上面 'Tests run'、'Rule violated' 或编译错误那几行。"
    exit "$status"
fi

echo
echo "== 2/3 用例数与跳过数 =="
reports=$(find claw4j-* -path "*/surefire-reports/TEST-*.xml" -type f 2>/dev/null)
if [ -z "$reports" ]; then
    echo "没有找到任何测试报告：一条用例都没跑，不能算通过。"
    exit 1
fi

stats=$(printf '%s\n' "$reports" | xargs awk '
    /<testsuite / {
        if (match($0, /tests="[0-9]+"/))    { t += substr($0, RSTART + 7,  RLENGTH - 8)  }
        if (match($0, /skipped="[0-9]+"/))  { s += substr($0, RSTART + 9,  RLENGTH - 10) }
        if (match($0, /failures="[0-9]+"/)) { f += substr($0, RSTART + 10, RLENGTH - 11) }
        if (match($0, /errors="[0-9]+"/))   { e += substr($0, RSTART + 8,  RLENGTH - 9)  }
    }
    END { printf "%d %d %d %d\n", t, s, f, e }')

read -r total skipped failures errors <<<"$stats"
echo "用例 ${total} 条，失败 ${failures}，错误 ${errors}，跳过 ${skipped}"

echo
echo "== 3/3 三个数 =="
printf '%-20s %6s %10s %10s\n' 模块 文件数 最大方法数 行覆盖率
red=0
for module in $MODULES; do
    if [ ! -d "$module/src/test" ]; then
        printf '%-20s %6s %10s %10s\n' "$module" 0 0 -
        continue
    fi

    files=$(find "$module/src/test" -name '*.java' | wc -l | tr -d ' ')
    max_methods=0
    for file in $(find "$module/src/test" -name '*.java'); do
        n=$(awk '/@Test|@ArchTest/ { c++ } END { print c + 0 }' "$file")
        [ "$n" -gt "$max_methods" ] && max_methods=$n
    done

    csv="$module/target/site/jacoco/jacoco.csv"
    if [ -f "$csv" ]; then
        coverage=$(awk -F, 'NR > 1 { missed += $8; covered += $9 }
            END { if (missed + covered == 0) print "n/a";
                  else printf "%.1f%%", 100 * covered / (missed + covered) }' "$csv")
    else
        coverage="-"
    fi

    printf '%-20s %6s %10s %10s\n' "$module" "$files" "$max_methods" "$coverage"

    [ "$files" -gt "$MAX_FILES" ] && { echo "  ↑ $module 测试文件超过 $MAX_FILES 个：多半还在按类写"; red=1; }
    [ "$max_methods" -gt "$MAX_METHODS" ] && { echo "  ↑ $module 单个文件超过 $MAX_METHODS 个方法：拆功能，别拆文件"; red=1; }
done

echo
if [ "$skipped" -ne 0 ]; then
    echo "红：有 $skipped 条用例被跳过。缺 key 就删掉那条，不许挂 @Disabled。"
    red=1
fi
if [ "$failures" -ne 0 ] || [ "$errors" -ne 0 ]; then
    echo "红：$failures 条失败、$errors 条错误。"
    red=1
fi

if [ "$red" -ne 0 ]; then
    exit 1
fi

echo "全绿：$total 条用例全过，跳过 0，行覆盖率均不低于 ${MIN_COVERAGE}%。"
