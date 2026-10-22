/**
 * 通道模块：所有"消息从哪来、往哪回"的适配代码。
 *
 * <p>按平台分包而不是按平台分模块。理由见 ADR-0010：通道之间共享同一套
 * 入站/出站语义、同一套幂等与限流策略，拆成独立模块只会让每加一个通道
 * 都要新建 POM、改父 POM 的 modules、改 CI 矩阵，而换来的隔离用
 * 包级 ArchUnit 规则同样拿得到。
 *
 * <p>包结构：
 * <ul>
 *   <li>{@code channel.spi} —— 契约，平台中立</li>
 *   <li>{@code channel.http} —— HTTP + SSE，不需要任何外部账号，永远可演示</li>
 *   <li>{@code channel.feishu} —— 飞书长连接，M2</li>
 *   <li>{@code channel.a2a} —— A2A Server，M3</li>
 * </ul>
 */
package io.github.claw4j.channel;
