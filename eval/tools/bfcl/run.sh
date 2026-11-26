#!/usr/bin/env bash
#
# 选择层评测（票 17）：用 Berkeley Function Calling Leaderboard 的评测器，测"选对没有"。
#
# 与 eval/tools/retrieval/run.sh（检索层）的分工：
#   - 检索层：一句话进来，前 k 个候选里有没有那个工具。零成本，不调模型。
#   - 选择层（本脚本）：候选给了，模型挑了哪个、参数填对没有、本来不该调的时候调没调。
#     **要真模型、要花钱**，所以手动跑，不进 CI。
#
# 用法：
#   ./eval/tools/bfcl/run.sh                     # 全量两档（simple_python 400 + irrelevance 240）
#   ./eval/tools/bfcl/run.sh --smoke             # 6 条冒烟，几秒钟，改脚本时先跑这个
#   ./eval/tools/bfcl/run.sh --from-score        # 不调模型，只按当前门槛重算分与报告
#   ./eval/tools/bfcl/run.sh --update-baseline   # 确认改好了，把分数钉成新基线
#
# 退出码：0 达标（绝对门与回归门都过）；1 有不达标或回归项（看报告「判定」表的结论列）；
#         2 环境没搭起来（缺 key / 缺依赖）。
# 绝对门（票 19）：simple_python ≥95%、irrelevance ≥90%。它与基线无关，--update-baseline
# 只跳过回归那一半，不会豁免它——所以更新完基线仍可能退出 1，那是质量没达标，不是写失败。

set -uo pipefail

# 脚本在 eval/tools/bfcl/ 下，仓库根往上三级（eval/rag 是两级，别照抄）。
cd "$(dirname "$0")/../../.." || exit 1

if [ -f .env ]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
fi

here="eval/tools/bfcl"
venv="$here/.venv"
tmpdir="$here/tmpdir"

smoke=0
from_score=0
passthrough=()
for arg in "$@"; do
    case "$arg" in
        --smoke) smoke=1 ;;
        --from-score) from_score=1 ;;
        *) passthrough+=("$arg") ;;
    esac
done

# 缺 key 是失败，不是跳过（与 eval/tools/retrieval/run.sh、eval/rag/run.sh 同一条规矩）。
if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
    echo "缺 DEEPSEEK_API_KEY：BFCL 的 DeepSeek handler 直接读这个变量。"
    echo "没有 key 时跑出来的不是「0 分」，是「什么都没跑」。"
    exit 2
fi

# ---------------------------------------------------------------------------
# venv：必须 Python 3.12 及以下
#
# bfcl-eval 把 numpy 硬锁在 1.26.4，而 numpy 1.26 没有 3.13 的 wheel，装的时候会
# 现场编译并失败（报一堆 C 编译警告，最后 metadata-generation-failed）。
# 所以这里不认「系统默认 python3」，自己找一个 3.10–3.12。
# ---------------------------------------------------------------------------

find_python() {
    if [ -n "${BFCL_PYTHON:-}" ]; then
        echo "$BFCL_PYTHON"
        return 0
    fi
    for candidate in python3.12 python3.11 python3.10; do
        if command -v "$candidate" >/dev/null 2>&1; then
            command -v "$candidate"
            return 0
        fi
    done
    return 1
}

# 临时目录：本机 python 的 tempfile 探测会失败（沙箱不放行随机命名的新建路径），
# 症状是每行输出前刷一句 "Error in sitecustomize ... No usable temporary directory"。
# boot.py 会把 tempfile.tempdir 直接指到这里，跳过探测。
# 用 /bin/mkdir 而不是裸 mkdir：PATH 上那个是沙箱的 shim。
/bin/mkdir -p "$tmpdir" || exit 2

install_deps() {
    # pip 自己也要 tempfile，所以同样要用引导的方式跑（见 boot.py 顶部注释）。
    "$venv/bin/python" - "$here/requirements.txt" "$tmpdir" <<'PY'
import runpy, sys, tempfile
tempfile.tempdir = sys.argv[2]
sys.argv = ["pip", "install", "-q", "--disable-pip-version-check", "-r", sys.argv[1]]
runpy.run_module("pip", run_name="__main__")
PY
    # 不看 pip 的退出码：这个沙箱不让它删临时目录，它会带着一堆 WARNING 以非 0 退出，
    # 但包装好了。判据改成"能不能 import"——事实比退出码可靠。
    "$venv/bin/python" -c "
import sys, tempfile
tempfile.tempdir = sys.argv[1]
import bfcl_eval
" "$tmpdir" 2>/dev/null
}

if [ ! -x "$venv/bin/python" ]; then
    py=$(find_python) || {
        echo "找不到 Python 3.10–3.12：bfcl-eval 的 numpy 被锁在 1.26.4，3.13 装不上。"
        echo "装一个（brew install python@3.12），或用 BFCL_PYTHON=/path/to/python3.12 指过去。"
        exit 2
    }
    echo "== 选择层：建 venv（首次，$py）=="
    "$py" -m venv "$venv" || exit 2
fi

# 只在 requirements.txt 比上次安装新时才装，避免每轮都跑一遍 pip。
stamp="$venv/.requirements.stamp"
if [ ! -f "$stamp" ] || [ "$here/requirements.txt" -nt "$stamp" ]; then
    echo "== 选择层：装依赖（首次或 requirements 变了）=="
    install_deps || {
        echo "依赖没装上。上面若是 pip 报 tempfile，先确认 $tmpdir 存在。"
        exit 2
    }
    /usr/bin/touch "$stamp"
fi

# ---------------------------------------------------------------------------
# 跑
# ---------------------------------------------------------------------------

# 冒烟用独立的根目录：BFCL 从 BFCL_PROJECT_ROOT 往下读写 test_case_ids / result / score。
# 不隔离的话，6 条的结果会盖掉正式结果里的同名题，下一轮全量分数就悄悄变了。
smoke_root=""
if [ "$smoke" -eq 1 ]; then
    # 必须是绝对路径：BFCL 会拿 PROJECT_ROOT 再拼 "result"，相对路径会被拼两次
    # （症状是产物落到 smoke/eval/tools/bfcl/smoke/result 这种套娃目录里）。
    smoke_root="$PWD/$here/smoke"
    /bin/mkdir -p "$smoke_root"
    /bin/cp "$here/test_case_ids_to_generate.json" "$smoke_root/test_case_ids_to_generate.json"
    export BFCL_PROJECT_ROOT="$smoke_root"
fi

if [ "$from_score" -eq 0 ]; then
    if [ "$smoke" -eq 1 ]; then
        echo "== 选择层 1/3：冒烟 6 条（读 test_case_ids_to_generate.json）=="
        "$venv/bin/python" "$here/boot.py" generate \
            --model DeepSeek-V3.2-Exp-FC \
            --test-category simple_python,irrelevance \
            --run-ids || exit $?
    else
        echo "== 选择层 1/3：生成（simple_python + irrelevance，全量 640 条）=="
        echo "   640 条大约一分半到十分钟（看并发）、1 元上下。只想过一遍脚本就加 --smoke。"
        "$venv/bin/python" "$here/boot.py" generate \
            --model DeepSeek-V3.2-Exp-FC \
            --test-category simple_python,irrelevance \
            --num-threads "${BFCL_THREADS:-8}" || exit $?
    fi

    # --partial-eval：我们只跑了两档，不写它的话 BFCL 会因为"其他类别没有结果"判失败。
    echo "== 选择层 2/3：按它的判据打分（AST 比对）=="
    "$venv/bin/python" "$here/boot.py" evaluate \
        --model DeepSeek-V3.2-Exp-FC \
        --test-category simple_python,irrelevance \
        --partial-eval || exit $?
else
    echo "== 选择层 1/3：跳过生成（--from-score）=="
fi

if [ "$smoke" -eq 1 ]; then
    # 6 条没法跟 640 条的基线比，所以只出报告、不判定。
    echo "== 选择层 3/3：冒烟报告（不判回归）=="
    "$venv/bin/python" "$here/report.py" \
        --score-dir "$smoke_root/score" \
        --reports-dir "$smoke_root/reports" \
        --no-baseline || exit $?
    echo "冒烟产物在 $smoke_root/，与正式结果、基线互不影响。"
    exit 0
fi

echo "== 选择层 3/3：判定与报告 =="
if [ ${#passthrough[@]} -gt 0 ]; then
    "$venv/bin/python" "$here/report.py" "${passthrough[@]}"
else
    "$venv/bin/python" "$here/report.py"
fi
