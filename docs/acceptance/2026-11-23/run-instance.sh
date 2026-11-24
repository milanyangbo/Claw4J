#!/bin/bash
# 验收起实例的统一入口：基础参数在这里，差异用环境变量与追加参数给。
#   DB=库名 USERS=白名单 AGENTDIR=人设目录（置空则不给） AGENTDEF=默认人设 RAG=true/false
# 用法：bash /tmp/run-accept.sh <日志路径> [额外的 --xxx 参数…]
cd /Users/ethanbyang/projects/java/Claw4J || exit 1
set -a; . ./.env; set +a

LOG=$1; shift
DB=${DB:-claw4j_accept_all}
USERS=${USERS-*}
AGENTDIR=${AGENTDIR-examples/agents}
AGENTDEF=${AGENTDEF-ops}
RAG=${RAG-true}

ARGS=(
  --spring.profiles.active=local
  --spring.docker.compose.enabled=false
  --spring.datasource.url="jdbc:postgresql://localhost:5432/$DB"
  --claw4j.advisor.session.dm-scope="${DMSCOPE-per-channel-peer}"
  --claw4j.channel.http.users="$USERS"
  --claw4j.rag.enabled="$RAG"
  --claw4j.advisor.cache.enabled=false
  --claw4j.skills.directory=examples/skills
  --claw4j.subagents.directory=examples/subagents
)
[ -n "$AGENTDIR" ] && ARGS+=(--claw4j.agent.directory="$AGENTDIR")
[ -n "$AGENTDEF" ] && ARGS+=(--claw4j.agent.default="$AGENTDEF")

exec java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar "${ARGS[@]}" "$@" 2>&1 | tee "$LOG"
