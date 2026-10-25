#!/bin/sh
#
# 测试文件的硬上限：每个模块 ≤10 个测试文件（代码量确实大最多 20），每个文件 ≤20 个测试方法。
#
# 为什么要把这条做成闸，而不是写进文档就完了：文档里的一句话，改到第三个模块时就没人记得了。
# 这条规则的可执行部分只有两个数字，正好适合机器检查——它不需要判断测试写得好不好，
# 只需回答"是不是又长回去了"。
#
# 为什么阈值是 10 和 20 两层：10 是默认目标，20 是"代码量确实大"的例外。机器判断不了
# 代码量大不大，所以 10–20 之间只提示、不失败；超过 20 一律失败——到了那个量级，
# 无论代码多少，都该按"被测对象"拆而不是继续往一个类里堆。
#
# 数的口径：源码里的 @Test / @ParameterizedTest / @RepeatedTest / @ArchTest 注解数。
# 它与 surefire 的执行条数不是一回事（参数化会展开、@Tag 组会被排除），
# 两个数字拿来说同一件事必然对不上，这里只数源码。
#
# 用法：
#   ./scripts/assert-test-quota.sh
#
# 退出码：0 = 全部在上限内；1 = 有模块或文件超限。
set -eu

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

max_files=10
hard_files=20
max_methods=20

fail=0
printf '%-22s %8s %8s %10s\n' 模块 文件数 方法数 单文件最大

for module in claw4j-*; do
    [ -d "$module/src/test" ] || continue

    files=0
    total=0
    worst=0
    worst_file=""
    for f in $(find "$module/src/test" -name '*.java' | sort); do
        # 注解常常单独占一行，所以不能要求它后面还有字符。
        # 只认注解名本身，避免把 @TestFactory 之类算进来。
        n=$(awk '
            {
                line = $0
                sub(/^[ \t]+/, "", line)
                if (line ~ /^@Test$/ || line ~ /^@ArchTest$/) { c++; next }
                if (line ~ /^@ParameterizedTest/ || line ~ /^@RepeatedTest/) { c++ }
            }
            END { print c + 0 }
        ' "$f")

        # 没有测试注解的是夹具/替身，不算测试文件——配额管的是"测试有几份"。
        [ "$n" -eq 0 ] && continue

        files=$((files + 1))
        total=$((total + n))
        if [ "$n" -gt "$worst" ]; then
            worst=$n
            worst_file=${f#"$module/src/test/java/"}
        fi
    done

    printf '%-22s %8s %8s %10s\n' "$module" "$files" "$total" "$worst"

    if [ "$files" -gt "$hard_files" ]; then
        echo "  ✗ ${module} 有 ${files} 个测试文件，超过硬上限 ${hard_files}。" >&2
        fail=1
    elif [ "$files" -gt "$max_files" ]; then
        echo "  ! ${module} 有 ${files} 个测试文件，超过默认 ${max_files}；只有代码量确实大才允许到 ${hard_files}。" >&2
    fi

    if [ "$worst" -gt "$max_methods" ]; then
        echo "  ✗ ${worst_file} 有 ${worst} 个测试方法，超过每文件上限 ${max_methods}。" >&2
        echo "    先问多出来的是新规则还是同一规则的新取值：新规则才留，取值合并，其余删。" >&2
        fail=1
    fi
done

if [ "$fail" -ne 0 ]; then
    exit 1
fi

echo "assert-test-quota: 全部在上限内"
