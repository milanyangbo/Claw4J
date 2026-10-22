/**
 * 领域模型。本包及其子包不得出现 Spring、Spring AI 或任何通道 SDK 的依赖 —— 这条边界由构建期检查保证，见 ADR-0004。
 *
 * <p>放这里的东西：Principal、Session 标识、Turn 这类不依赖框架就能表达的概念。
 */
package io.github.claw4j.domain;
