#!/usr/bin/env bash
#
# 真实链路的 irrelevance 评测（票 28）：
#   BFCL 的 240 条"不该调"问句 → 我们的检索给 top5 → 模型决策 → 两个分数。
#
# 用法：
#   ./eval/tools/live/run.sh              # 全量（导出 ~2 分钟 + 240 次模型调用）
#   ./eval/tools/live/run.sh --smoke 20   # 只跑前 20 条，验证链路通
#   ./eval/tools/live/run.sh --from-results  # 不调模型，只按上次明细重算与重写报告
#
# 退出码：0 达标（bfcl ≥90% 且 production 100%）；1 不达标；2 环境没搭起来。
# 这一层**没有回归基线**：第一次跑完才知道"正常"是多少，钉基线是下一件小事。

set -uo pipefail

cd "$(dirname "$0")/../../.." || exit 1

if [ -f .env ]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
fi

suffix=()
skip_export=0
while [ $# -gt 0 ]; do
    case "$1" in
        --smoke) suffix+=("--smoke" "${2:-20}"); shift 2; continue ;;
        --from-results) skip_export=1; suffix+=("--from-results") ;;
        *) suffix+=("$1") ;;
    esac
    shift
done

if [ -z "${QWEN_API_KEY:-}" ] || [ -z "${DEEPSEEK_API_KEY:-}" ]; then
    echo "缺 key：导出要 QWEN_API_KEY（真 embedding），决策要 DEEPSEEK_API_KEY（真模型）。"
    exit 2
fi

echo "== 1/3：问句清单自检 =="
python3 eval/tools/live/make_questions.py --check || exit $?

if [ "$skip_export" -eq 0 ]; then
    echo "== 2/3：导出 240 题的 top5（Java test，@Tag eval，不进回归）=="
    ./mvnw -o -pl claw4j-eval -am -Peval test \
        -Dtest=LiveCandidatesExportTest \
        -Dsurefire.failIfNoSpecifiedTests=false || exit $?
else
    echo "== 2/3：跳过导出（--from-results）=="
fi

echo "== 3/3：逐题问模型 =="
# 空数组在 bash 3.2（macOS 自带）配 set -u 会报 unbound，所以分两支写。
if [ ${#suffix[@]} -gt 0 ]; then
    python3 eval/tools/live/run_live.py "${suffix[@]}"
else
    python3 eval/tools/live/run_live.py
fi
