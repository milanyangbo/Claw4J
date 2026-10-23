# 02: 本地依赖编排（docker compose 起 Postgres 与 Redis）

**What to build:** 开发者在这台机器上敲一条命令，Postgres（带 pgvector）和 Redis 就起来了，应用启动后能连上，不需要手工建库、装扩展、填连接串。

**Blocked by:** 01

**Status:** resolved

- [x] `compose.yaml` 定义两个服务：Postgres（pgvector 镜像）与 Redis
- [x] pgvector 扩展在初始化脚本里创建，不用手工进容器执行
- [x] 应用通过 `spring-boot-docker-compose` 自动拉起并注入连接信息（`@ServiceConnection`）
- [x] 没有 Docker 时应用仍能启动（容器相关配置可选，不阻断本地开发）
- [x] 启动日志能看到数据源与 Redis 连接成功

## Answer

容器已真实拉起并验证：`vector` 扩展在 `pg_extension` 里、`business` schema 已建、Redis 返回 PONG。应用无容器启动时 1.4 秒起来。

三个踩到的坑，都写进代码注释了：

1. **Docker Hub 在这台机器上连不上**（`registry-1.docker.io` 超时）。`compose.yaml` 里镜像名做成 `${CLAW4J_POSTGRES_IMAGE:-pgvector/pgvector:pg16}` 这种可覆盖形式，默认保持官方镜像（仓库可移植），本地用 `.env`（已 gitignore）换成镜像站。`.env.example` 里写了注释。
2. **数据源默认值必须给全**。只写 `spring.docker.compose.enabled=false` 不够，`DataSourceAutoConfiguration` 会因为没 url 直接启动失败。默认给了 `jdbc:postgresql://localhost:5432/claw4j`，Hikari 是懒连接，不会在启动时真连。
3. **`spring-boot-maven-plugin` 的 repackage 要显式绑定**。父 POM 用 BOM import 而不是继承 `spring-boot-starter-parent`，插件不会自动绑 repackage，打出来的 jar 没有 main manifest。
