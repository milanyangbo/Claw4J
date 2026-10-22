/**
 * 通道 SPI：任何一个通道实现都要遵守的契约。
 *
 * <p>本包的编译期边界（由 {@code ChannelArchitectureTest} 用 ArchUnit 校验）：
 * <ul>
 *   <li>不允许出现任何具体平台 SDK 的 import（飞书 {@code com.lark..}、
 *       Telegram 等）。SPI 必须对所有平台中立，否则加一个通道就得改契约。</li>
 *   <li>只允许依赖 domain，不允许依赖 agent。通道不知道回答是怎么算出来的。</li>
 * </ul>
 *
 * <p>各实现按平台分包（{@code channel.http}、{@code channel.feishu}），
 * 平台 SDK 只在自己的包里出现，并且以 optional 依赖引入，不传递给下游模块。
 */
package io.github.claw4j.channel.spi;
