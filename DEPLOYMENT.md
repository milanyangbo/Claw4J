# Local Deployment

This guide covers local infrastructure and service startup for Claw4J. The runnable services default to `CLAW4J_NACOS_SERVER_ADDR=127.0.0.1:8848`, namespace `public`, group `CLAW4J_DEV_GROUP`, and environment `local`.

## Nacos

For local smoke tests, use Docker to start a standalone Nacos 3.x server with embedded Derby storage. Pin the image to the current GA line `nacos/nacos-server:v3.2.4`; do not use the older `v2.5.1` image or floating `latest` tag for repeatable local tests.

> This Docker setup is for local development and smoke testing only. Do not use it for production because authentication is disabled and the server runs as a single instance.

### Start Nacos

Start a Docker-compatible daemon first, such as Docker Desktop, Colima, OrbStack, or Rancher Desktop. Verify the daemon before starting Nacos:

```bash
docker ps
```

If `docker ps` reports `Cannot connect to the Docker daemon`, start or install one Docker-compatible runtime first. For example, with Colima:

```bash
brew install colima
colima start --cpu 2 --memory 4
```

Then run:

```bash
NACOS_AUTH_TOKEN="$(openssl rand -base64 32)"
NACOS_AUTH_IDENTITY_VALUE="$(openssl rand -hex 16)"

docker run --name claw4j-nacos \
  -e MODE=standalone \
  -e NACOS_AUTH_ENABLE=false \
  -e NACOS_AUTH_TOKEN="${NACOS_AUTH_TOKEN}" \
  -e NACOS_AUTH_IDENTITY_KEY=serverIdentity \
  -e NACOS_AUTH_IDENTITY_VALUE="${NACOS_AUTH_IDENTITY_VALUE}" \
  -p 18080:8080 \
  -p 8848:8848 \
  -p 9848:9848 \
  -d nacos/nacos-server:v3.2.4
```

The generated shell variables only live in the current terminal session, but Docker stores their values in the created container. Re-run these two variable commands only when recreating the container.

If the container already exists, restart it instead:

```bash
docker start claw4j-nacos
```

Watch startup logs until Nacos reports that it started successfully:

```bash
docker logs -f claw4j-nacos
```

### Verify Nacos

Check that the Nacos API is reachable:

```bash
curl 'http://127.0.0.1:8848/nacos/v3/client/ns/instance/list?serviceName=claw4j-api-gateway&groupName=CLAW4J_DEV_GROUP&namespaceId=public'
```

The console is available at:

```text
http://127.0.0.1:18080/next/
```

## Sentinel Dashboard

Use Docker to start a local Sentinel Dashboard aligned with the current Sentinel runtime. Pin the image to `bladex/sentinel-dashboard:1.8.9`; do not use a floating `latest` tag for repeatable local tests.

> This Docker setup is for local development and smoke testing only. The `bladex/sentinel-dashboard` image is a pinned community image that packages Sentinel Dashboard `1.8.9`; replace it with an internally reviewed image before production use.

Keep Dashboard startup here, and keep service-call traffic checks in [Sentinel Smoke Tests](SENTINEL_SMOKE_TESTS.md).

### Start Sentinel Dashboard

Verify the Docker-compatible daemon is running, as described in the Nacos section, then run:

```bash
docker run --name claw4j-sentinel-dashboard \
  -p 8858:8858 \
  -p 18719:8719 \
  -d bladex/sentinel-dashboard:1.8.9
```

If the container already exists, restart it instead:

```bash
docker start claw4j-sentinel-dashboard
```

Watch startup logs until the Dashboard reports that it started successfully:

```bash
docker logs -f claw4j-sentinel-dashboard
```

### Verify Sentinel Dashboard

The console is available at:

```text
http://127.0.0.1:8858/
```

The default local Dashboard login is `sentinel` / `sentinel`. The container exposes the Dashboard HTTP port on `8858` and maps its internal Sentinel command port `8719` to host port `18719`, so Gateway and Orchestrator can keep their configured client ports.

## Services

Build the services with their reactor dependencies:

```bash
mvn -q package -DskipTests -pl claw4j-api-gateway,claw4j-orchestrator,claw4j-a2a-broker -am
```

Start services in separate terminals:

```bash
java -Duser.home="$PWD/target/runtime-home/gateway" \
  -Dcsp.sentinel.log.dir="$PWD/target/sentinel-logs/gateway" \
  -jar claw4j-api-gateway/target/claw4j-api-gateway-0.0.1-SNAPSHOT.jar

java -Duser.home="$PWD/target/runtime-home/orchestrator" \
  -Dcsp.sentinel.log.dir="$PWD/target/sentinel-logs/orchestrator" \
  -jar claw4j-orchestrator/target/claw4j-orchestrator-0.0.1-SNAPSHOT.jar

java -Duser.home="$PWD/target/runtime-home/a2a-broker" \
  -jar claw4j-a2a-broker/target/claw4j-a2a-broker-0.0.1-SNAPSHOT.jar
```

Optional explicit local overrides:

```bash
CLAW4J_NACOS_SERVER_ADDR=127.0.0.1:8848 \
CLAW4J_NACOS_NAMESPACE=public \
CLAW4J_NACOS_GROUP=CLAW4J_DEV_GROUP \
CLAW4J_ENVIRONMENT=local \
java -Duser.home="$PWD/target/runtime-home/gateway" \
  -Dcsp.sentinel.log.dir="$PWD/target/sentinel-logs/gateway" \
  -jar claw4j-api-gateway/target/claw4j-api-gateway-0.0.1-SNAPSHOT.jar
```

### Verify Service Registration

After a service starts, verify that Nacos can see it:

```bash
curl 'http://127.0.0.1:8848/nacos/v3/client/ns/instance/list?serviceName=claw4j-api-gateway&groupName=CLAW4J_DEV_GROUP&namespaceId=public'
curl 'http://127.0.0.1:8848/nacos/v3/client/ns/instance/list?serviceName=claw4j-orchestrator&groupName=CLAW4J_DEV_GROUP&namespaceId=public'
curl 'http://127.0.0.1:8848/nacos/v3/client/ns/instance/list?serviceName=claw4j-a2a-broker&groupName=CLAW4J_DEV_GROUP&namespaceId=public'
```

Stop a service with `Ctrl+C`, then rerun the matching query to confirm the instance is removed or marked unavailable.

## Stop Local Containers

```bash
docker stop claw4j-sentinel-dashboard
docker rm claw4j-sentinel-dashboard
docker stop claw4j-nacos
docker rm claw4j-nacos
```
