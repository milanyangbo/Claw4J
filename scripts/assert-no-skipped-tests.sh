#!/bin/sh
#
# 跳过的测试一律算失败。
#
# 为什么单独立一条命令：跳过的绿和通过的绿在 surefire 的输出里长得一模一样，
# 而"数据库测试整类没跑"这种失效姿势恰恰藏在跳过里——26 条从未执行，
# 构建照样绿，报数的人会把它算进"全绿"（M1 收尾时真踩过一次，当时报的是 177）。
# CI 的 -Dclaw4j.dbTests=required 只挡"没有 Docker"这一种情形，
# 挡不住别的静默跳过路径（今天全库的 @EnabledIf 都是数据库门禁，那条链正是曾经失效的那条）。
#
# 用法（跑完 ./mvnw verify 之后）：
#   ./scripts/assert-no-skipped-tests.sh
#
# 退出码：0 = 一个都没跳过；1 = 有跳过（或压根没找到报告，那说明 verify 没跑）。
set -eu

repo_root=$(cd "$(dirname "$0")/.." && pwd)

reports=$(find "$repo_root" -type f -name 'TEST-*.xml' \
    \( -path '*/target/surefire-reports/*' -o -path '*/target/failsafe-reports/*' \) \
    | sort)

if [ -z "$reports" ]; then
    echo "assert-no-skipped-tests: 没找到任何 TEST-*.xml，先跑 ./mvnw verify 再来。" >&2
    exit 1
fi

# 从每个报告的 <testsuite> 根元素上摘四个计数。属性写在一行里，
# 且 surefire / failsafe 都按这个顺序输出，所以摘第一个匹配就够。
attr() {
    awk -v name="$1" '
        /<testsuite/ {
            if (match($0, name "=\"[0-9]+\"")) {
                print substr($0, RSTART + length(name) + 2, RLENGTH - length(name) - 3)
                exit
            }
        }
    ' "$2"
}

total_tests=0
total_failures=0
total_errors=0
total_skipped=0
files=0
offenders=""

for report in $reports; do
    [ -n "$(attr tests "$report")" ] || {
        echo "assert-no-skipped-tests: $report 里没读到 <testsuite> 计数，报告格式变了，这条闸可能已经失灵。" >&2
        exit 1
    }
    files=$((files + 1))
    total_tests=$((total_tests + $(attr tests "$report")))
    total_failures=$((total_failures + $(attr failures "$report")))
    total_errors=$((total_errors + $(attr errors "$report")))
    skipped=$(attr skipped "$report")
    total_skipped=$((total_skipped + skipped))
    if [ "$skipped" -gt 0 ]; then
        offenders="${offenders}  skipped=${skipped}  ${report}\n"
    fi
done

if [ "$total_skipped" -gt 0 ]; then
    echo "assert-no-skipped-tests: 有 ${total_skipped} 个测试被跳过，跳过的绿不算绿。" >&2
    printf "${offenders}" >&2
    echo "  常见原因：本机没有 Docker（数据库那一类会整类跳过），" >&2
    echo "  或有 Docker 但拉不动镜像（用 CLAW4J_POSTGRES_IMAGE 换镜像源，见 README）。" >&2
    exit 1
fi

echo "assert-no-skipped-tests: ${files} 个报告，tests=${total_tests} failures=${total_failures} errors=${total_errors} skipped=0"
