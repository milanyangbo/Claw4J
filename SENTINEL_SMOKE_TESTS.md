# Sentinel Smoke Tests

This guide verifies Claw4J Sentinel global limiting, tenant isolation, circuit breaking, fallback behavior, dashboard visibility, dynamic rule refresh, rollback, and cleanup. Start Nacos, Sentinel Dashboard, Gateway, and Orchestrator by following [Local Deployment](DEPLOYMENT.md) before running these checks.

## Defaults

The proof paths use these local defaults:

- Gateway resource: `claw4j-gateway-ingress`
- Orchestrator resource: `claw4j-orchestrator-agent-call`
- Nacos namespace: `public`
- Nacos group: `CLAW4J_DEV_GROUP`
- Sentinel Dashboard: `127.0.0.1:8858`
- Sentinel Dashboard login: `sentinel` / `sentinel`
- Gateway endpoint: `http://127.0.0.1:8080/internal/gateway/sentinel/guarded`
- Orchestrator endpoint: `http://127.0.0.1:8081/internal/orchestrator/sentinel/agent-call`

For the local Nacos 3 container from [Local Deployment](DEPLOYMENT.md), admin config APIs require the server identity header. Load it from the running container:

```bash
NACOS_AUTH_IDENTITY_VALUE="$(docker inspect claw4j-nacos --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | sed -n 's/^NACOS_AUTH_IDENTITY_VALUE=//p')"
```

## Publish Gateway Rules

Publish a low global QPS rule and a relaxed tenant rule:

```bash
cat >/tmp/claw4j-api-gateway-sentinel-flow-rules.json <<'EOF'
[
  {
    "resource": "claw4j-gateway-ingress",
    "limitApp": "default",
    "grade": 1,
    "count": 1,
    "strategy": 0,
    "controlBehavior": 0
  }
]
EOF

cat >/tmp/claw4j-api-gateway-sentinel-param-flow-rules.json <<'EOF'
[
  {
    "resource": "claw4j-gateway-ingress",
    "grade": 1,
    "paramIdx": 0,
    "count": 100,
    "controlBehavior": 0,
    "durationInSec": 1
  }
]
EOF

curl -sS -X POST 'http://127.0.0.1:8848/nacos/v3/admin/cs/config' \
  -H "serverIdentity: ${NACOS_AUTH_IDENTITY_VALUE}" \
  -d 'dataId=claw4j-api-gateway-sentinel-flow-rules.json' \
  -d 'groupName=CLAW4J_DEV_GROUP' \
  -d 'namespaceId=public' \
  --data-urlencode content@/tmp/claw4j-api-gateway-sentinel-flow-rules.json

curl -sS -X POST 'http://127.0.0.1:8848/nacos/v3/admin/cs/config' \
  -H "serverIdentity: ${NACOS_AUTH_IDENTITY_VALUE}" \
  -d 'dataId=claw4j-api-gateway-sentinel-param-flow-rules.json' \
  -d 'groupName=CLAW4J_DEV_GROUP' \
  -d 'namespaceId=public' \
  --data-urlencode content@/tmp/claw4j-api-gateway-sentinel-param-flow-rules.json
```

Wait a few seconds for the Sentinel datasource to refresh, then run the Gateway proof call:

```bash
for i in $(seq 1 5); do
  curl -sS \
    -H "X-Request-Id: sentinel-global-${i}" \
    -H 'X-Tenant-Id: smoke-tenant-a' \
    -H 'X-User-Id: smoke-user' \
    -H "X-Idempotency-Key: sentinel-global-${i}" \
    'http://127.0.0.1:8080/internal/gateway/sentinel/guarded'
  echo
done
```

Expected evidence: at least one response is successful, and one later response shows the standardized rate-limit error:

```json
{"code":"CLAW4J-SENTINEL-429","message":"Gateway Sentinel rate limit triggered","requestId":"sentinel-global-2","success":false}
```

## Verify Tenant Isolation

Raise the global QPS rule and lower the tenant parameter-flow rule:

```bash
cat >/tmp/claw4j-api-gateway-sentinel-flow-rules.json <<'EOF'
[
  {
    "resource": "claw4j-gateway-ingress",
    "limitApp": "default",
    "grade": 1,
    "count": 100,
    "strategy": 0,
    "controlBehavior": 0
  }
]
EOF

cat >/tmp/claw4j-api-gateway-sentinel-param-flow-rules.json <<'EOF'
[
  {
    "resource": "claw4j-gateway-ingress",
    "grade": 1,
    "paramIdx": 0,
    "count": 1,
    "controlBehavior": 0,
    "durationInSec": 1
  }
]
EOF

curl -sS -X POST 'http://127.0.0.1:8848/nacos/v3/admin/cs/config' \
  -H "serverIdentity: ${NACOS_AUTH_IDENTITY_VALUE}" \
  -d 'dataId=claw4j-api-gateway-sentinel-flow-rules.json' \
  -d 'groupName=CLAW4J_DEV_GROUP' \
  -d 'namespaceId=public' \
  --data-urlencode content@/tmp/claw4j-api-gateway-sentinel-flow-rules.json

curl -sS -X POST 'http://127.0.0.1:8848/nacos/v3/admin/cs/config' \
  -H "serverIdentity: ${NACOS_AUTH_IDENTITY_VALUE}" \
  -d 'dataId=claw4j-api-gateway-sentinel-param-flow-rules.json' \
  -d 'groupName=CLAW4J_DEV_GROUP' \
  -d 'namespaceId=public' \
  --data-urlencode content@/tmp/claw4j-api-gateway-sentinel-param-flow-rules.json
```

Drive tenant A above quota, then send one tenant B request:

```bash
for i in $(seq 1 4); do
  curl -sS \
    -H "X-Request-Id: sentinel-tenant-a-${i}" \
    -H 'X-Tenant-Id: smoke-tenant-a' \
    -H 'X-User-Id: smoke-user' \
    -H "X-Idempotency-Key: sentinel-tenant-a-${i}" \
    'http://127.0.0.1:8080/internal/gateway/sentinel/guarded'
  echo
done

curl -sS \
  -H 'X-Request-Id: sentinel-tenant-b-1' \
  -H 'X-Tenant-Id: smoke-tenant-b' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Idempotency-Key: sentinel-tenant-b-1' \
  'http://127.0.0.1:8080/internal/gateway/sentinel/guarded'
```

Expected evidence: tenant A receives `CLAW4J-SENTINEL-429`, while tenant B still returns `"guardResult":"allowed"`.

## Publish Orchestrator Degrade Rule

Publish a low-volume error-ratio circuit rule:

```bash
cat >/tmp/claw4j-orchestrator-sentinel-degrade-rules.json <<'EOF'
[
  {
    "resource": "claw4j-orchestrator-agent-call",
    "grade": 1,
    "count": 0.5,
    "timeWindow": 2,
    "minRequestAmount": 2,
    "statIntervalMs": 1000
  }
]
EOF

curl -sS -X POST 'http://127.0.0.1:8848/nacos/v3/admin/cs/config' \
  -H "serverIdentity: ${NACOS_AUTH_IDENTITY_VALUE}" \
  -d 'dataId=claw4j-orchestrator-sentinel-degrade-rules.json' \
  -d 'groupName=CLAW4J_DEV_GROUP' \
  -d 'namespaceId=public' \
  --data-urlencode content@/tmp/claw4j-orchestrator-sentinel-degrade-rules.json
```

Drive the protected Agent-call proof path above the error threshold:

```bash
for i in $(seq 1 2); do
  curl -sS \
    -H "X-Request-Id: sentinel-agent-fail-${i}" \
    -H 'X-Tenant-Id: smoke-tenant-a' \
    -H 'X-User-Id: smoke-user' \
    -H "X-Idempotency-Key: sentinel-agent-fail-${i}" \
    'http://127.0.0.1:8081/internal/orchestrator/sentinel/agent-call?fail=true'
  echo
done

curl -sS \
  -H 'X-Request-Id: sentinel-agent-fallback-1' \
  -H 'X-Tenant-Id: smoke-tenant-a' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Idempotency-Key: sentinel-agent-fallback-1' \
  'http://127.0.0.1:8081/internal/orchestrator/sentinel/agent-call'
```

Expected evidence: the failure calls return the shared downstream-unavailable error, and a later call returns an explicit fallback payload:

```json
{"success":true,"message":"success","data":{"resourceName":"claw4j-orchestrator-agent-call","requestId":"sentinel-agent-fallback-1","tenantId":"smoke-tenant-a","fallbackApplied":true,"resultMessage":"CLAW4J-SENTINEL-503: Circuit breaker is open"}}
```

## Verify Recovery

Wait for the recovery window and send a successful probe:

```bash
sleep 3
curl -sS \
  -H 'X-Request-Id: sentinel-agent-recover-1' \
  -H 'X-Tenant-Id: smoke-tenant-a' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Idempotency-Key: sentinel-agent-recover-1' \
  'http://127.0.0.1:8081/internal/orchestrator/sentinel/agent-call'
```

Expected evidence: the response shows `"fallbackApplied":false` and `"resultMessage":"agent-call-success"`.

## Verify Dashboard Visibility

Open the Sentinel Dashboard from [Local Deployment](DEPLOYMENT.md), then confirm both services are visible after traffic reaches them. For a scriptable check, log in and query the machine registry:

```bash
curl -sS -c /tmp/claw4j-sentinel-dashboard-cookies.txt \
  -X POST 'http://127.0.0.1:8858/auth/login?username=sentinel&password=sentinel'

curl -sS -b /tmp/claw4j-sentinel-dashboard-cookies.txt \
  'http://127.0.0.1:8858/app/claw4j-api-gateway/machines.json'

curl -sS -b /tmp/claw4j-sentinel-dashboard-cookies.txt \
  'http://127.0.0.1:8858/app/claw4j-orchestrator/machines.json'
```

Copy the `ip` and `port` values from those responses, then query the resource tree. If Dashboard itself occupies a Sentinel command port, a service may register on the next available port; use the machine registry output as the source of truth.

```bash
GATEWAY_MACHINE_IP=192.168.1.110
GATEWAY_MACHINE_PORT=8719
ORCHESTRATOR_MACHINE_IP=192.168.1.110
ORCHESTRATOR_MACHINE_PORT=8720

curl -sS -b /tmp/claw4j-sentinel-dashboard-cookies.txt \
  "http://127.0.0.1:8858/resource/machineResource.json?ip=${GATEWAY_MACHINE_IP}&port=${GATEWAY_MACHINE_PORT}&searchKey=claw4j-gateway-ingress"

curl -sS -b /tmp/claw4j-sentinel-dashboard-cookies.txt \
  "http://127.0.0.1:8858/resource/machineResource.json?ip=${ORCHESTRATOR_MACHINE_IP}&port=${ORCHESTRATOR_MACHINE_PORT}&searchKey=claw4j-orchestrator-agent-call"
```

Expected evidence:

- `claw4j-api-gateway` should show `claw4j-gateway-ingress`.
- `claw4j-orchestrator` should show `claw4j-orchestrator-agent-call`.

Dashboard visibility is only observability evidence. Treat blocked curl responses as the enforcement proof.

## Rollback

Publish relaxed rules without restarting services:

```bash
cat >/tmp/claw4j-api-gateway-sentinel-flow-rules.json <<'EOF'
[
  {
    "resource": "claw4j-gateway-ingress",
    "limitApp": "default",
    "grade": 1,
    "count": 100,
    "strategy": 0,
    "controlBehavior": 0
  }
]
EOF

cat >/tmp/claw4j-api-gateway-sentinel-param-flow-rules.json <<'EOF'
[
  {
    "resource": "claw4j-gateway-ingress",
    "grade": 1,
    "paramIdx": 0,
    "count": 100,
    "controlBehavior": 0,
    "durationInSec": 1
  }
]
EOF

cat >/tmp/claw4j-orchestrator-sentinel-degrade-rules.json <<'EOF'
[
  {
    "resource": "claw4j-orchestrator-agent-call",
    "grade": 1,
    "count": 1,
    "timeWindow": 2,
    "minRequestAmount": 100,
    "statIntervalMs": 1000
  }
]
EOF
```

Re-publish the three files with the same `curl -X POST` commands used above, wait a few seconds, then repeat the Gateway and Orchestrator calls to verify normal success responses.

## Cleanup

Remove the smoke-test rule DataIds when finished:

```bash
curl -sS -X DELETE 'http://127.0.0.1:8848/nacos/v3/admin/cs/config?dataId=claw4j-api-gateway-sentinel-flow-rules.json&groupName=CLAW4J_DEV_GROUP&namespaceId=public' \
  -H "serverIdentity: ${NACOS_AUTH_IDENTITY_VALUE}"
curl -sS -X DELETE 'http://127.0.0.1:8848/nacos/v3/admin/cs/config?dataId=claw4j-api-gateway-sentinel-param-flow-rules.json&groupName=CLAW4J_DEV_GROUP&namespaceId=public' \
  -H "serverIdentity: ${NACOS_AUTH_IDENTITY_VALUE}"
curl -sS -X DELETE 'http://127.0.0.1:8848/nacos/v3/admin/cs/config?dataId=claw4j-orchestrator-sentinel-degrade-rules.json&groupName=CLAW4J_DEV_GROUP&namespaceId=public' \
  -H "serverIdentity: ${NACOS_AUTH_IDENTITY_VALUE}"
```
