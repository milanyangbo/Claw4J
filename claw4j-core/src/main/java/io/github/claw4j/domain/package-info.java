/**
 * 领域模型。本包及其子包不得出现 Spring、Spring AI 或任何通道 SDK 的依赖，这条边界由构建期
 * 检查保证，见 ADR-0004。
 *
 * <p>它比 {@code channel.*} 那两个包还严一档：连 slf4j 都不许依赖（{@code CoreBoundariesTest}）。
 * 这不是洁癖——domain 是唯一"换成任何框架都还能原样搬走"的东西，{@code AgentPort} 这条接缝
 * 就落在这里；它一旦认识日志门面，就说明有人在里面写实现而不是写契约了。
 *
 * <p>它住在 {@code claw4j-core} 这个 artifact 里，包名保持 {@code io.github.claw4j.domain}
 * （ADR-0015）。包名不动是因为 {@code domain.port} 是 ADR-0010 里"接缝"的专有叫法，
 * 改名等于让那份文档里的每句话都失效。
 *
 * <p>放这里的东西：Principal、权限、审批这类不依赖框架就能表达的概念。
 */
package io.github.claw4j.domain;
