## 1. Common Module Setup

- [x] 1.1 Update `claw4j-common/pom.xml` with the minimal dependencies needed for Spring web exception contracts, Jackson JSON handling, and tests; verify `mvn validate -pl claw4j-common` succeeds.
- [x] 1.2 Create approved common package areas under `claw4j-common/src/main/java/com/claw4j/common/annotation`, `exception`, `constant`, `util`, and `dto`, including package-level documentation for `constant`; verify no other common source packages are created.

## 2. Shared Contracts

- [x] 2.1 Add `ErrorCode.java`, `Claw4jException.java`, `BusinessException.java`, and `GlobalExceptionHandler.java` under `claw4j-common/src/main/java/com/claw4j/common/exception`; verify known and unexpected exception mapping with common module tests.
- [x] 2.2 Add `ApiResponse.java` and `ErrorResponse.java` under `claw4j-common/src/main/java/com/claw4j/common/dto`; verify success and error response shapes with common module tests.
- [x] 2.3 Add `Audit.java`, `Tool.java`, and `RateLimit.java` under `claw4j-common/src/main/java/com/claw4j/common/annotation`; verify runtime retention and required metadata with reflection tests.
- [x] 2.4 Add `CommonConstants.java` under `claw4j-common/src/main/java/com/claw4j/common/constant`; verify constants compile and replace the root-level placeholder constant class if it is no longer needed.
- [x] 2.5 Add `JsonUtil.java` and `IdUtil.java` under `claw4j-common/src/main/java/com/claw4j/common/util`; verify JSON success/failure paths and non-empty ID generation with common module tests.

## 3. Service Integration

- [x] 3.1 Add direct `claw4j-common` dependencies to `claw4j-api-gateway/pom.xml`, `claw4j-orchestrator/pom.xml`, `claw4j-tool-executor/pom.xml`, `claw4j-knowledge-memory/pom.xml`, and `claw4j-a2a-broker/pom.xml`; verify each service resolves through `mvn clean compile -pl <service-module> -am`.
- [x] 3.2 Update the five service startup classes to scan `com.claw4j` so common Spring components can be discovered; verify each startup class remains under its approved module package root.
- [x] 3.3 Verify no service module declares a direct Maven dependency on another service module by running Maven dependency analysis or a dependency tree check.

## 4. Validation

- [x] 4.1 Run `mvn clean test -pl claw4j-common` and verify all common module tests pass.
- [x] 4.2 Run `mvn clean compile` from the repository root and verify all six modules compile.
- [x] 4.3 Run `mvn test` from the repository root and verify the full test lifecycle passes.
- [x] 4.4 Run `openspec validate "add-common-foundation" --strict` and verify the change artifacts remain valid.
