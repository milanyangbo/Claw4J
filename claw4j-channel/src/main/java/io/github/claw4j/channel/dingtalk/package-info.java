/**
 * 钉钉通道中与平台无关的那一半：一条已解出的入站消息、一个把文本推回去的出口、
 * 一个能按通道名找到的投递实现。
 *
 * <p>这三样只认 JDK 与 {@code claw4j-core} 的契约，不知道钉钉怎么验签、怎么换 token；
 * 那两件事在子包 {@code channel.dingtalk.client}，界由 {@code ChannelBoundaryTest} 的两条规则守住。
 *
 * <p>钉钉的入站是明文 JSON，签名在 header 里（{@code sign}，HMAC-SHA256）。没有"加解密"这一层
 * 可复用：这一侧唯一的密码学动作是验签，一次 HMAC 就结束。也正因为报文是明文，
 * 验签就是唯一的门，漏了它等于把一个公网端点直接交给模型。
 *
 * <p>没有 {@code .sdk} 子包：钉钉有官方 SDK（{@code dingtalk-stream-sdk-java}），但它面向
 * Stream 长连接模式，而这一版走 HTTP 回调，用不上。换 token 与发消息都只是一个 POST 一个 JSON，
 * 为这两件事拖进一个 SDK，换来的是"它什么时候跟进 Spring Boot 4"这类与本事无关的维护问题
 * （ADR-0009:16 记过钉钉 SDK 在 Maven Central 上没命中）。
 */
package io.github.claw4j.channel.dingtalk;
