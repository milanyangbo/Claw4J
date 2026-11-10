/**
 * 通道模块：各平台"消息从哪来、往哪回"的适配代码。
 *
 * <p>按平台分包而不是按平台分模块（ADR-0010）。各通道共用同一套入站／出站语义、
 * 同一套幂等与限流策略，拆成独立模块只会让每加一个通道都要新建 POM、改父 POM 的 modules、
 * 改 CI 矩阵；包级 ArchUnit 规则给出的隔离是一样的。
 *
 * <p>子包：
 * <ul>
 *   <li>{@code channel.http}：HTTP 与 SSE，不需要任何外部账号，永远可演示</li>
 *   <li>{@code channel.feishu}：飞书，SDK 长连接</li>
 *   <li>{@code channel.wecom}：企业微信，加密 XML 回调</li>
 *   <li>{@code channel.dingtalk}：钉钉，明文 JSON 回调</li>
 *   <li>{@code channel.slack}：Slack，Events API 的 HTTP 回调</li>
 * </ul>
 *
 * <p>契约（{@code channel.spi}）与入站编排（{@code channel.kernel}）不在本模块，
 * 在 {@code claw4j-core} 里，由那个模块的 {@code CoreBoundaryTest} 用白名单守着。
 */
package io.github.claw4j.channel;
