#!/usr/bin/env bash
#
# 一条命令跑完全部单元测试，并守住两个数：
#   1. 所有用例全过      —— 有失败、有错误就红（跳过也算红：绿的但没验，比失败更危险）
#   2. 行覆盖率 ≥ 60%    —— 父 POM 的 JaCoCo check 绑在 verify 上，破线构建直接失败
#
# **不打印用例条数**：条数不是质量信号（一个类里堆 20 个同形状的断言照样绿）。
# 指标是覆盖率与"有没有红"，条数只活在 surefire 报告里，要看得自己去看。
#
# 另外三步：开机先查删除词（ADR-0030 删掉的那一层，源码里一个词都不许剩），
# 再查票面（resolved 的票打了 ✅ 的用例名必须真的存在；**还没提交的新票**必须交代裁决在哪
# 并带「TDD 用例清单」——历史票不追，2026-11-20 用户裁决），
# 最后打印每模块的文件数与最大方法数（上限 14 / 26），超了判红。
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

MODULES="claw4j-core claw4j-channel claw4j-capability claw4j-agent claw4j-example-sales claw4j-example-mcp-server claw4j-app claw4j-eval"

# 两个上限跟 `docs/agents/testing.md` 是同一对数（2026-11-18 从 10 / 20 抬到实测值）。
#
# 原来是 10 / 20，被票 01 – 04 按模块追加用例顶穿了：ExecToolTest 26 个方法、
# ToolProfileTest 22、KnowledgeRetrievalTest 21，另有四个模块文件数过了 10。
# 抬而不是拆，是因为这些文件的边界本来就是**功能边界**（一个文件就是一件工具、
# 一类检索的全部行为），硬拆只会造出一堆同名不同义的测试文件。
# 代价要认：这两个数是"到顶"，下一批用例进来之前必须先拆或先合并，别再加、也别再抬。
MAX_FILES=14
MAX_METHODS=26
MIN_COVERAGE=60

# ADR-0030 阶段 4 删掉的那一层，词必须真的没了（票 04 判据）。放在构建之前：
# 这是秒级检查，让它先说话，别让编译器一行一行往外挤。
#
# 逐词调用，不写成一次 `a\|b\|c` 的交替：这台机器上 grep 可能是 toybox，
# 它不认 BRE 的 `\|` 而且**不报错**——交替写法的闸会永远绿，比没有闸更坏。
# 同理不加 `--include`（toybox 未必认），用 find 自己筛 *.java。
GONE_WORDS="AuthorizationAdvisor ToolPermission ToolPermissionRegistry canReadTable canPerform PermissionFingerprint RoleDirectory RoleGrant X-Claw4j-Role owner_role"

echo "== 1/5 删除词：这十个词必须一个都不剩 =="
leftover=""
for word in $GONE_WORDS; do
    hits=$(find . -name '*.java' -not -path '*/target/*' -type f -exec grep -Hn "$word" {} + 2>/dev/null)
    [ -n "$hits" ] && leftover="${leftover}${hits}
"
done
if [ -n "$leftover" ]; then
    printf '%s' "$leftover"
    echo
    echo "红：上面这些行还引用已下线的类型/配置键。注释里提到也算——判据是「词没了」，"
    echo "历史原因写进 ADR-0030，不要留在源码注释里。"
    exit 1
fi

echo "== 2/5 票面：✅ 的用例名必须真的存在；新票必须有 ADR 指针与 TDD 用例清单 =="
./scripts/check-ticket-cases.sh
status=$?
if [ "$status" -ne 0 ]; then
    exit "$status"
fi

echo
echo "== 3/5 构建 + 测试 + 覆盖率闸（./mvnw -o clean verify）=="
./mvnw -o clean verify
status=$?
if [ "$status" -ne 0 ]; then
    echo
    echo "构建失败：看上面 'Tests run'、'Rule violated' 或编译错误那几行。"
    exit "$status"
fi

echo
echo "== 4/5 单元测试全过（失败 / 错误 / 跳过 都必须为 0）=="
reports=$(find claw4j-* -path "*/surefire-reports/TEST-*.xml" -type f 2>/dev/null)
if [ -z "$reports" ]; then
    echo "没有找到任何测试报告：一条用例都没跑，不能算通过。"
    exit 1
fi

stats=$(printf '%s\n' "$reports" | xargs awk '
    /<testsuite / {
        if (match($0, /skipped="[0-9]+"/))  { s += substr($0, RSTART + 9,  RLENGTH - 10) }
        if (match($0, /failures="[0-9]+"/)) { f += substr($0, RSTART + 10, RLENGTH - 11) }
        if (match($0, /errors="[0-9]+"/))   { e += substr($0, RSTART + 8,  RLENGTH - 9)  }
    }
    END { printf "%d %d %d\n", s, f, e }')

read -r skipped failures errors <<<"$stats"
echo "失败 ${failures}，错误 ${errors}，跳过 ${skipped}"

echo
echo "== 5/5 三个数 =="
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

echo "全绿：单元测试全过（失败 0、错误 0、跳过 0），行覆盖率均不低于 ${MIN_COVERAGE}%。"
