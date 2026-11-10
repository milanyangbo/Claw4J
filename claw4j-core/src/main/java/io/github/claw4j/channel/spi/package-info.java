/**
 * 通道 SPI：任何一个通道实现都要遵守的契约。
 *
 * <p>本包所在的模块（{@code claw4j-core}）是零框架的：编译期只有 slf4j 一条依赖
 * （{@code domain} 并进了同一个模块，不再是一个单独的坐标）。这条边界是模块级的，不靠自觉：
 * 飞书 SDK 在 {@code claw4j-channel} 里，那个模块依赖本模块，所以依赖方向写反就编译不过；
 * 而"有人在 pom 里加了 Spring"由 validate 阶段的 enforcer 当场拦下。
 *
 * <p>所以接一个新通道（Telegram、Slack、企业微信）时，依赖本模块就够了：拿到的是这里的契约与
 * {@code channel.kernel} 的入站编排，classpath 里不会出现 {@code com.larksuite.oapi}。
 * spi 与编排不放在 {@code claw4j-channel} 里的原因就是这个，与分层是否好看无关。
 *
 * <p>本包的编译期边界（由 {@code CoreBoundariesTest} 用 ArchUnit 校验）：
 * <ul>
 *   <li>不允许出现任何具体平台 SDK 的 import（飞书 {@code com.lark..}、Telegram 等）。
 *       SPI 必须对所有平台中立，否则加一个通道就得改契约。</li>
 *   <li>只允许依赖 domain，不允许依赖 agent。通道不知道回答是怎么算出来的。</li>
 * </ul>
 *
 * <p>各实现按平台分包（{@code channel.http}、{@code channel.feishu}），平台 SDK 只在自己的
 * 模块与自己的包里出现。
 */
package io.github.claw4j.channel.spi;
