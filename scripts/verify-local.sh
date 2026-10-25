#!/bin/sh
#
# 一条命令跑完本地的三道闸。CI 没接进来之前，这就是全部防线。
#
# 三道依次是：测试文件配额（只读源码）→ clean verify（含 JaCoCo 行覆盖率红线）→ 跳过数必须为 0。
# 覆盖率不用单独一步：`jacoco:check` 已经绑在 verify 上，红了构建就停。
#
# 为什么要有它：跑全量其实需要两句咒语，而两句都只活在操作过这台机器的人的脑子里——
#   1. Maven 不读 .env，DEEPSEEK_API_KEY 得先 export（ADR-0011：没 key 是红不是跳过，
#      所以这一步不能省，省了就是"没连真模型却以为连了"）。
#   2. 非交互 shell 不 source ~/.zshrc，JAVA_HOME 常常是空的，./mvnw 直接失败。
# 新会话、子代理、换一台机器，这两句都会重新踩一遍。咒语该待在脚本里，不该待在记忆里。
#
# 用法：
#   ./scripts/verify-local.sh            # 完整版
#   ./scripts/verify-local.sh -Dtest=XxxTest   # 参数照原样传给 mvnw
#
# 退出码：0 = 构建绿、覆盖率过线、一个都没跳过；非 0 = 其中任一不成立。
set -eu

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

# .env 里有 key，也可能有镜像站写法（CLAW4J_POSTGRES_IMAGE）。有就加载，没有就靠现有环境。
if [ -f .env ]; then
    set -a
    . ./.env
    set +a
fi

# 找 JDK 21：已经设了就用，没设就按"这台机器上可能有"的顺序探一遍。
if [ -z "${JAVA_HOME:-}" ]; then
    if [ -x /usr/libexec/java_home ]; then
        JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
    fi
    if [ -z "${JAVA_HOME:-}" ] && [ -x "$HOME/soft/jdk-21.jdk/Contents/Home/bin/java" ]; then
        JAVA_HOME="$HOME/soft/jdk-21.jdk/Contents/Home"
    fi
    if [ -z "${JAVA_HOME:-}" ]; then
        echo "verify-local: 没找到 JDK 21，先 export JAVA_HOME=<jdk21 的 Contents/Home> 再来。" >&2
        exit 1
    fi
    export JAVA_HOME
fi

# 数据库测试现在无条件跑（Testcontainers，Docker 在就起一次性容器），
# 不再有"缺 Docker 就跳过"的开关——跳过数必须为 0 这条闸本来就不允许那种开关存在。

# 先查配额：它只读源码，不用等构建，超了立刻停——省得跑完十分钟才被告知"文件又多了"。
./scripts/assert-test-quota.sh

echo "verify-local: JAVA_HOME=$JAVA_HOME"
echo "verify-local: ./mvnw clean verify $*"
# 必须 clean：surefire 报告留在 target 里，改名、合并、删测试之后旧报告还在，
# 后面的测试数对账会把上一代的数量一起算进去（M2 收尾时就报成 596）。
./mvnw -B -ntp clean verify "$@"

./scripts/assert-no-skipped-tests.sh
