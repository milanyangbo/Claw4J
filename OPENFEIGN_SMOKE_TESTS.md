# OpenFeign Smoke Tests

This guide verifies Claw4J internal service calls through OpenFeign, Spring Cloud LoadBalancer, and fallback degradation. Start Nacos and the services first by following [Local Deployment](DEPLOYMENT.md).

## Required Headers

All proof calls require these internal context headers:

- `X-Request-Id`
- `X-Tenant-Id`
- `X-User-Id`
- `X-Idempotency-Key`

## Internal Calls

Gateway calls Orchestrator by the `claw4j-orchestrator` service name:

```bash
curl -sS \
  -H 'X-Request-Id: smoke-gateway-orchestrator-001' \
  -H 'X-Tenant-Id: smoke-tenant' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Idempotency-Key: smoke-gateway-orchestrator-001' \
  'http://127.0.0.1:8080/internal/gateway/orchestrator/status'
```

Expected response shape:

```json
{"success":true,"message":"success","data":{"serviceName":"claw4j-orchestrator","role":"orchestrator","instancePort":8081,"requestId":"smoke-gateway-orchestrator-001"}}
```

Orchestrator calls A2A Broker by the `claw4j-a2a-broker` service name:

```bash
curl -sS \
  -H 'X-Request-Id: smoke-orchestrator-a2a-001' \
  -H 'X-Tenant-Id: smoke-tenant' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Idempotency-Key: smoke-orchestrator-a2a-001' \
  'http://127.0.0.1:8081/internal/orchestrator/a2a/status'
```

Expected response shape:

```json
{"success":true,"message":"success","data":{"serviceName":"claw4j-a2a-broker","role":"a2a-broker","instancePort":8084,"requestId":"smoke-orchestrator-a2a-001"}}
```

## Load Balancing

For an isolated smoke run, set the same dedicated group in every service terminal before starting Gateway, Orchestrator, and A2A Broker:

```bash
export CLAW4J_NACOS_GROUP=CLAW4J_FEIGN_SMOKE
```

If a service is already running without this group, restart it after exporting the variable.

Start a second Orchestrator instance on a different port:

```bash
java -Duser.home="$PWD/target/runtime-home/orchestrator-18081" \
  -jar claw4j-orchestrator/target/claw4j-orchestrator-0.0.1-SNAPSHOT.jar \
  --server.port=18081
```

Start a second A2A Broker instance on a different port:

```bash
java -Duser.home="$PWD/target/runtime-home/a2a-broker-18084" \
  -jar claw4j-a2a-broker/target/claw4j-a2a-broker-0.0.1-SNAPSHOT.jar \
  --server.port=18084
```

Run repeated Gateway to Orchestrator calls and verify more than one `instancePort` appears:

```bash
for i in $(seq 1 12); do
  curl -sS \
    -H "X-Request-Id: lb-gateway-orchestrator-${i}" \
    -H 'X-Tenant-Id: smoke-tenant' \
    -H 'X-User-Id: smoke-user' \
    -H "X-Idempotency-Key: lb-gateway-orchestrator-${i}" \
    'http://127.0.0.1:8080/internal/gateway/orchestrator/status' \
    | sed -n 's/.*"instancePort":\([0-9][0-9]*\).*/\1/p'
done | sort | uniq -c
```

Run repeated Orchestrator to A2A Broker calls and verify more than one `instancePort` appears:

```bash
for i in $(seq 1 12); do
  curl -sS \
    -H "X-Request-Id: lb-orchestrator-a2a-${i}" \
    -H 'X-Tenant-Id: smoke-tenant' \
    -H 'X-User-Id: smoke-user' \
    -H "X-Idempotency-Key: lb-orchestrator-a2a-${i}" \
    'http://127.0.0.1:8081/internal/orchestrator/a2a/status' \
    | sed -n 's/.*"instancePort":\([0-9][0-9]*\).*/\1/p'
done | sort | uniq -c
```

## Fallback

Stop all Orchestrator instances, then Gateway should return the standardized downstream-unavailable response:

```bash
curl -sS \
  -H 'X-Request-Id: fallback-gateway-orchestrator-001' \
  -H 'X-Tenant-Id: smoke-tenant' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Idempotency-Key: fallback-gateway-orchestrator-001' \
  'http://127.0.0.1:8080/internal/gateway/orchestrator/status'
```

Expected response shape:

```json
{"code":"CLAW4J-RPC-001","message":"Orchestrator service is unavailable","requestId":"fallback-gateway-orchestrator-001","success":false}
```

Stop all A2A Broker instances, then Orchestrator should return the standardized downstream-unavailable response:

```bash
curl -sS \
  -H 'X-Request-Id: fallback-orchestrator-a2a-001' \
  -H 'X-Tenant-Id: smoke-tenant' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Idempotency-Key: fallback-orchestrator-a2a-001' \
  'http://127.0.0.1:8081/internal/orchestrator/a2a/status'
```

Expected response shape:

```json
{"code":"CLAW4J-RPC-001","message":"A2A Broker service is unavailable","requestId":"fallback-orchestrator-a2a-001","success":false}
```
