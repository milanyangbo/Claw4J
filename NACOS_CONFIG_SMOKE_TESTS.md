# Nacos Config Smoke Tests

This guide verifies Claw4J dynamic configuration loading, hot refresh, invalid-value fallback, and rollback. Start Nacos by following [Local Deployment](DEPLOYMENT.md) before running these checks.

## Defaults

The runnable services default to these local Nacos Config values:

- Server address: `127.0.0.1:8848`
- Namespace: `public`
- Group: `CLAW4J_DEV_GROUP`
- Shared DataId: `claw4j-shared.yaml`
- Gateway service DataId: `claw4j-api-gateway.yaml`

The application imports both DataIds as optional config sources, so a missing config center or missing DataId should not block local startup.

For the local Nacos 3 container from [Local Deployment](DEPLOYMENT.md), admin config APIs require the server identity header. Load it from the running container:

```bash
NACOS_AUTH_IDENTITY_VALUE="$(docker inspect claw4j-nacos --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | sed -n 's/^NACOS_AUTH_IDENTITY_VALUE=//p')"
```

## Publish Config

Publish a shared default threshold:

```bash
cat >/tmp/claw4j-shared.yaml <<'EOF'
claw4j:
  dynamic-config:
    gateway:
      smoke-rate-limit-threshold: 11
      demo-message: shared-demo
EOF

curl -sS -X POST 'http://127.0.0.1:8848/nacos/v3/admin/cs/config' \
  -H "serverIdentity: ${NACOS_AUTH_IDENTITY_VALUE}" \
  -d 'dataId=claw4j-shared.yaml' \
  -d 'groupName=CLAW4J_DEV_GROUP' \
  -d 'namespaceId=public' \
  --data-urlencode content@/tmp/claw4j-shared.yaml
```

Publish a Gateway-specific override:

```bash
cat >/tmp/claw4j-api-gateway.yaml <<'EOF'
claw4j:
  dynamic-config:
    gateway:
      smoke-rate-limit-threshold: 21
      demo-message: gateway-demo
EOF

curl -sS -X POST 'http://127.0.0.1:8848/nacos/v3/admin/cs/config' \
  -H "serverIdentity: ${NACOS_AUTH_IDENTITY_VALUE}" \
  -d 'dataId=claw4j-api-gateway.yaml' \
  -d 'groupName=CLAW4J_DEV_GROUP' \
  -d 'namespaceId=public' \
  --data-urlencode content@/tmp/claw4j-api-gateway.yaml
```

## Start Gateway

Build and start Gateway with the local Nacos defaults:

```bash
mvn -q package -DskipTests -pl claw4j-api-gateway -am

java -Duser.home="$PWD/target/runtime-home/gateway" \
  -jar claw4j-api-gateway/target/claw4j-api-gateway-0.0.1-SNAPSHOT.jar
```

## Verify Load

Query the dynamic config proof endpoint:

```bash
curl -sS 'http://127.0.0.1:8080/internal/gateway/config/dynamic'
```

Expected response shape:

```json
{"success":true,"message":"success","data":{"effectiveThreshold":21,"demoMessage":"gateway-demo","fallbackApplied":false,"invalidKey":""}}
```

## Verify Hot Refresh

Change the Gateway-specific threshold without restarting Gateway:

```bash
cat >/tmp/claw4j-api-gateway.yaml <<'EOF'
claw4j:
  dynamic-config:
    gateway:
      smoke-rate-limit-threshold: 33
      demo-message: refreshed-demo
EOF

curl -sS -X POST 'http://127.0.0.1:8848/nacos/v3/admin/cs/config' \
  -H "serverIdentity: ${NACOS_AUTH_IDENTITY_VALUE}" \
  -d 'dataId=claw4j-api-gateway.yaml' \
  -d 'groupName=CLAW4J_DEV_GROUP' \
  -d 'namespaceId=public' \
  --data-urlencode content@/tmp/claw4j-api-gateway.yaml

sleep 3
curl -sS 'http://127.0.0.1:8080/internal/gateway/config/dynamic'
```

The response should show `"effectiveThreshold":33`, `"demoMessage":"refreshed-demo"`, and `"fallbackApplied":false`.

## Verify Invalid-Value Fallback

Publish an invalid threshold:

```bash
cat >/tmp/claw4j-api-gateway.yaml <<'EOF'
claw4j:
  dynamic-config:
    gateway:
      smoke-rate-limit-threshold: abc
      demo-message: invalid-demo
EOF

curl -sS -X POST 'http://127.0.0.1:8848/nacos/v3/admin/cs/config' \
  -H "serverIdentity: ${NACOS_AUTH_IDENTITY_VALUE}" \
  -d 'dataId=claw4j-api-gateway.yaml' \
  -d 'groupName=CLAW4J_DEV_GROUP' \
  -d 'namespaceId=public' \
  --data-urlencode content@/tmp/claw4j-api-gateway.yaml

sleep 3
curl -sS 'http://127.0.0.1:8080/internal/gateway/config/dynamic'
```

The response should keep Gateway running, show `"fallbackApplied":true`, identify `claw4j.dynamic-config.gateway.smoke-rate-limit-threshold` as `invalidKey`, keep using the previous valid threshold, and still expose `"demoMessage":"invalid-demo"`.

## Verify Rollback

Restore the previous valid threshold:

```bash
cat >/tmp/claw4j-api-gateway.yaml <<'EOF'
claw4j:
  dynamic-config:
    gateway:
      smoke-rate-limit-threshold: 21
      demo-message: gateway-demo
EOF

curl -sS -X POST 'http://127.0.0.1:8848/nacos/v3/admin/cs/config' \
  -H "serverIdentity: ${NACOS_AUTH_IDENTITY_VALUE}" \
  -d 'dataId=claw4j-api-gateway.yaml' \
  -d 'groupName=CLAW4J_DEV_GROUP' \
  -d 'namespaceId=public' \
  --data-urlencode content@/tmp/claw4j-api-gateway.yaml

sleep 3
curl -sS 'http://127.0.0.1:8080/internal/gateway/config/dynamic'
```

The response should show `"effectiveThreshold":21`, `"demoMessage":"gateway-demo"`, and `"fallbackApplied":false` without restarting Gateway.

## Inspect History

Nacos records config history for the DataId:

```bash
curl -sS 'http://127.0.0.1:8848/nacos/v3/admin/cs/history/list?dataId=claw4j-api-gateway.yaml&groupName=CLAW4J_DEV_GROUP&namespaceId=public&pageNo=1&pageSize=10' \
  -H "serverIdentity: ${NACOS_AUTH_IDENTITY_VALUE}"
```

Use the history output or the Nacos console to trace and roll back operational changes.

## Cleanup

Remove the smoke-test DataIds when finished:

```bash
curl -sS -X DELETE 'http://127.0.0.1:8848/nacos/v3/admin/cs/config?dataId=claw4j-api-gateway.yaml&groupName=CLAW4J_DEV_GROUP&namespaceId=public' \
  -H "serverIdentity: ${NACOS_AUTH_IDENTITY_VALUE}"
curl -sS -X DELETE 'http://127.0.0.1:8848/nacos/v3/admin/cs/config?dataId=claw4j-shared.yaml&groupName=CLAW4J_DEV_GROUP&namespaceId=public' \
  -H "serverIdentity: ${NACOS_AUTH_IDENTITY_VALUE}"
```
