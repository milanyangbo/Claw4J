#!/usr/bin/env bash
#
# 跑评测集：真模型、真库、真工具清单，一共五十来条。
#
# 为什么它不是 verify.sh 的一部分：一次全跑十几分钟、几分钱，而且要联网。
# ADR-0019 写的是「与装配用例分开跑、分开看」。
# 但它也不是"没人管"——用例集自洽与判分口径都在 verify.sh 里（不调模型）：
#   EvalCaseSetTest  用例写坏了、工具改名了，回归里当场红
#   EvalScorerTest   判分口径坏了，回归里当场红
#
# 用法：
#   ./scripts/eval.sh                       # 全跑
#   ./scripts/eval.sh sales knowledge       # 只跑某几类（sales / knowledge / tools / authz / memory）
#   ./scripts/eval.sh --case sales-weekly-east
#
# 退出码：0 = 全部用例判定通过；非 0 = 有用例没过、或者缺 key、或者构建失败。

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

if [ -f .env ]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
fi

# 缺 key 是失败，不是跳过（ADR-0011 的落地规则）。
# 两把都要：DeepSeek 出答案，通义出 embedding（知识库那十二条要靠它检索）。
missing=""
[ -n "${DEEPSEEK_API_KEY:-}" ] || missing="$missing DEEPSEEK_API_KEY"
[ -n "${QWEN_API_KEY:-}" ] || missing="$missing QWEN_API_KEY"
if [ -n "$missing" ]; then
    echo "缺环境变量：$missing"
    echo "评测要真模型与真 embedding，没有 key 说明这台机器跑不了它——那是失败，不是跳过。"
    exit 2
fi

filter=""
while [ $# -gt 0 ]; do
    case "$1" in
        --case)
            shift
            if [ $# -eq 0 ]; then
                echo "--case 后面要跟一个用例 id，例如 sales-weekly-east"
                exit 2
            fi
            filter="${filter:+$filter,}$1"
            ;;
        -h|--help)
            sed -n '2,20p' "$0"
            exit 0
            ;;
        *)
            filter="${filter:+$filter,}$1"
            ;;
    esac
    shift
done

echo "== 评测：${filter:-全部用例} =="
if [ -n "$filter" ]; then
    ./mvnw -o -pl claw4j-eval -am -Peval test \
        -Dtest=EvalRunTest \
        -Dsurefire.failIfNoSpecifiedTests=false \
        "-Dclaw4j.eval.filter=$filter"
else
    ./mvnw -o -pl claw4j-eval -am -Peval test \
        -Dtest=EvalRunTest \
        -Dsurefire.failIfNoSpecifiedTests=false
fi
status=$?

# 报告摘要：最近一份。测试自己已经打了三行，这里只把落盘位置与总数再点一次，
# 因为"分数存在哪儿"是这一层最容易被问到的（两份报告要能拿来比）。
latest=$(ls -t eval/reports/*.json 2>/dev/null | head -1)
if [ -n "$latest" ]; then
    echo
    echo "最近一份报告：$latest"
    awk '/"totals"/,/}/' "$latest" | sed 's/^  //'
fi

exit "$status"
