#!/usr/bin/env bash
#
# 工具检索评测（票 12）：Java 只导出，Python 侧用 ir_measures 算分。
#
# 与 eval/rag/run.sh 同一个形状，少一把 key（那边还要 judge，这边指标是纯集合运算）。
# 唯一要的环境变量是 embedding（Java 导出要建向量库、真算向量）。
#
# 用法：
#   ./eval/tools/retrieval/run.sh                          # 全部配置
#   ./eval/tools/retrieval/run.sh --configs hybrid-0.0     # 只跑一档
#   ./eval/tools/retrieval/run.sh --from-report            # 不重跑导出，只按当前门槛重算
#   ./eval/tools/retrieval/run.sh --cross-check            # 加做两套指标的一致性校验
#   ./eval/tools/retrieval/run.sh --update-baseline        # 确认改好了，把分数钉成新基线
#
# 退出码：0 全过；1 有回归或零候选题失败；2 环境没搭起来（缺 key / 缺依赖 / 缺基线）。

set -uo pipefail

# 脚本在 eval/tools/retrieval/ 下，仓库根往上三级。
cd "$(dirname "$0")/../../.." || exit 1

if [ -f .env ]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
fi

skip_export=0
passthrough=()
for arg in "$@"; do
    case "$arg" in
        --from-report) skip_export=1 ;;
        *) passthrough+=("$arg") ;;
    esac
done

if [ "$skip_export" -eq 0 ]; then
    # 缺 embedding key 是失败，不是跳过：hybrid 那三档要靠它建向量库，缺了导出会直接崩。
    # regex / lucene 不需要它，但"跑一半再说"比"开跑前说清楚"更贵。
    if [ -z "${QWEN_API_KEY:-}" ]; then
        echo "缺 QWEN_API_KEY：导出的 hybrid 三档要真 embedding。"
        echo "只跑 regex / lucene 的话，可以绕过导出直接用已有的 reports/。"
        exit 2
    fi
    echo "== 工具检索 1/2：导出（Java test，@Tag eval，不进回归）=="
    ./mvnw -o -pl claw4j-eval -am -Peval test \
        -Dtest=ToolRetrievalExportTest \
        -Dsurefire.failIfNoSpecifiedTests=false || exit $?
else
    echo "== 工具检索 1/2：跳过导出（--from-report 只重算分数与判定）=="
fi

venv="eval/tools/retrieval/.venv"
if [ ! -x "$venv/bin/python" ]; then
    echo "== 工具检索：建 venv（首次）=="
    python3 -m venv "$venv" || exit 2
fi
# 依赖只在缺的时候装。
#
# 两处与"直接跑 pip"不同，都是踩出来的：
#   - 用 `python -m pip` 而不是 `bin/pip`：venv 目录被搬过之后，`bin/pip` 的 shebang
#     还指着旧路径，直接跑会说找不到解释器。走 `-m` 就与 shebang 无关。
#   - 判据是"能不能 import"而不是 pip 的退出码：这台机器上 pip 清理临时目录会被拦，
#     它带着一堆 WARNING 以非 0 退出，但包其实装好了。
if ! "$venv/bin/python" -c "import ir_measures" >/dev/null 2>&1; then
    echo "== 工具检索：装依赖（缺 ir_measures）=="
    "$venv/bin/python" -m pip install -q --disable-pip-version-check \
        -r eval/tools/retrieval/requirements.txt || exit 2
    "$venv/bin/python" -c "import ir_measures" >/dev/null 2>&1 || {
        echo "ir_measures 还是 import 不了：venv 坏了，删掉 $venv 重跑本脚本。"
        exit 2
    }
fi

echo "== 工具检索 2/2：算分（ir_measures，零成本）=="
# 空数组在 bash 3.2（macOS 自带）配 set -u 会报 unbound，所以分两支写。
if [ ${#passthrough[@]} -gt 0 ]; then
    "$venv/bin/python" eval/tools/retrieval/run_ir.py "${passthrough[@]}"
else
    "$venv/bin/python" eval/tools/retrieval/run_ir.py
fi
