#!/usr/bin/env bash
#
# 检索评测（RAG）：Java 只导出，Python 侧用 Ragas 算 L1 检索 + L2 生成。
#
# 这是这一层唯一的入口。原先它挂在 scripts/eval.sh 的 ragas 分支里，搬出来的理由：
# 那一支的每一步都只服务 RAG（导出用例、venv、门槛、报告），而 scripts/eval.sh 的主路径
# 是通用评测（sales / knowledge / tools / memory 四类、五十来条）。两件事挤在一个文件里，
# 改个门槛要翻到别人的脚本里找，改个用例过滤又要小心别碰坏另一边。
#
# 用法：
#   ./eval/rag/run.sh                     # L1 + L2（L2 花约 1 元）
#   ./eval/rag/run.sh --skip-judge        # 只跑 L1，零成本，改检索参数时用这个
#   ./eval/rag/run.sh --cutoffs 3,5,8,15
#   ./eval/rag/run.sh --update-baseline   # 确认改好了，把 L2 的对照基线钉下来
#   ./eval/rag/run.sh --from-score eval/rag/reports/score.json   # 不调模型，只重建判定与报告
#
# 退出码：0 = 全部判定通过；1 = 有不达标/回归/存疑；2 = 缺 key 或环境没搭起来。

set -uo pipefail

# 脚本在 eval/rag/ 下，仓库根往上两级。
cd "$(dirname "$0")/../.." || exit 1

if [ -f .env ]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
fi

# 缺 key 是失败，不是跳过（ADR-0011 的落地规则）。两把都要：
# DeepSeek 出答案并当 judge，通义出 embedding（检索那一路离不开它）。
missing=""
[ -n "${DEEPSEEK_API_KEY:-}" ] || missing="$missing DEEPSEEK_API_KEY"
[ -n "${QWEN_API_KEY:-}" ] || missing="$missing QWEN_API_KEY"
if [ -n "$missing" ]; then
    echo "缺环境变量：$missing"
    echo "评测要真模型与真 embedding，没有 key 说明这台机器跑不了它——那是失败，不是跳过。"
    exit 2
fi

# --from-score 的语义是"不调模型、只按当前门槛重算旧分数"，所以不能先跑导出：
# 那一步要起 Testcontainers、真检索、真回答，一分钱一分钟都省不下来。
skip_export=0
for arg in "$@"; do
    case "$arg" in
        --from-score|--from-score=*) skip_export=1 ;;
    esac
done

if [ "$skip_export" -eq 0 ]; then
    echo "== 检索评测 1/2：导出（Java test，@Tag eval，不进回归）=="
    ./mvnw -o -pl claw4j-eval -am -Peval test \
        -Dtest=RetrievalExportTest \
        -Dsurefire.failIfNoSpecifiedTests=false || exit $?
else
    echo "== 检索评测 1/2：跳过导出（--from-score 只重算旧分数）=="
fi

venv="eval/rag/.venv"
if [ ! -x "$venv/bin/python" ]; then
    echo "== 检索评测：建 venv（首次）=="
    python3 -m venv "$venv" || exit 2
fi
# 每次都对齐一次依赖：requirements 加了包时 venv 不会自己装，
# 症状是跑到一半报"缺依赖"。
"$venv/bin/pip" install -q --disable-pip-version-check -r eval/rag/requirements.txt || exit 2

echo "== 检索评测 2/2：算分（L1 不调模型；L2 用 judge，加 --skip-judge 可只跑 L1）=="
"$venv/bin/python" eval/rag/run_ragas.py "$@"
