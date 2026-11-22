#!/usr/bin/env bash
# 值班现场采集：时间、负载、几个端口的健康/连通性。只读，不改任何东西。
#
# 用法：collect.sh [port ...]
# 退出码永远 0 —— "连不上"是结论的一部分，不是脚本失败。
set -uo pipefail

ports=("$@")
if [ ${#ports[@]} -eq 0 ]; then
  ports=(8080 9090 8082)
fi

printf '值班简报 %s\n' "$(date '+%Y-%m-%d %H:%M:%S (%z)')"
printf '  主机：%s %s，负载 %s\n' "$(uname -s)" "$(uname -m)" \
  "$(sysctl -n vm.loadavg 2>/dev/null | tr -d '{}' | awk '{print $1, $2, $3}' || echo '取不到')"
printf '  进程内没有权限看别的进程（沙箱），端口一律只测连通性\n'

unreachable=""
for port in "${ports[@]}"; do
  case "$port" in
    ''|*[!0-9]*) printf '  %s  跳过：不是端口号\n' "$port"; continue ;;
  esac
  health=$(curl -s -m 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:${port}/actuator/health" 2>/dev/null)
  if [ "$health" = "200" ]; then
    body=$(curl -s -m 3 "http://127.0.0.1:${port}/actuator/health" 2>/dev/null | tr -d '\n')
    printf '  127.0.0.1:%s  health=UP    %s\n' "$port" "${body:0:80}"
  else
    # 不是 actuator（例如 MCP server）：退一步只测 TCP 能不能连上。
    code=$(curl -s -m 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:${port}/" 2>/dev/null)
    if [ "$code" = "000" ]; then
      printf '  127.0.0.1:%s  http=000（连不上）\n' "$port"
      unreachable="${unreachable} ${port}"
    else
      printf '  127.0.0.1:%s  http=%s（在听，不是 actuator）\n' "$port" "$code"
    fi
  fi
done

if [ -n "$unreachable" ]; then
  printf '  结论：上述 unreachable 的端口在这台机器上没有服务在听：%s\n' "$unreachable"
else
  printf '  结论：本次测的端口都有人应答\n'
fi
exit 0
