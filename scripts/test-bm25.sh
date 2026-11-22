#!/usr/bin/env bash
#
# 按需跑 BM25 那一路的真容器测试（claw4j-app 的 Bm25SearchTest，3 条）。
#
# 它带 @Tag("docker")，被 surefire 默认排除，所以 ./scripts/verify.sh 不碰它——
# 是"这次没选它"，不是"跑了再跳过"（verify.sh 把 skipped != 0 判成失败）。
# 这个脚本就是把默认的排除组换成 docker 组的入口（-Pdocker）。
#
# 为什么还要 -am 和 failIfNoSpecifiedTests=false：
#   - -am：app 依赖另外八个模块的 SNAPSHOT，本机仓库里通常没有 install 过，
#         直接 -pl claw4j-app 会报 "POM ... is missing"；
#   - failIfNoSpecifiedTests=false：-Dtest 一限定，没被选中的模块会因"零匹配"报错。
#
# 用法：
#   ./scripts/test-bm25.sh                                     # 上游官方镜像
#   ./scripts/test-bm25.sh docker.m.daocloud.io/paradedb/paradedb:latest
#
# 镜像可以走位置参数或 PARADEDB_IMAGE。本地没有会现场拉；拉不动就把镜像站地址传进来。
# 守护进程没开就别跑这个——既然是自己选的这一组，没有容器就让它红，不做静默跳过。

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

IMAGE="${1:-${PARADEDB_IMAGE:-paradedb/paradedb:pg16}}"

echo "镜像：${IMAGE}"
exec ./mvnw -o -Pdocker -pl claw4j-app -am test -Dtest=Bm25SearchTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    "-Dclaw4j.test.paradedb.image=${IMAGE}"
