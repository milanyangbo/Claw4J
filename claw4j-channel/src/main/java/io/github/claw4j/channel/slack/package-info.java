/**
 * Slack 通道中与平台无关的那一半：一条已解出的入站消息、一个把文本推回去的出口、
 * 一个能按通道名找到的投递实现，以及"频道 + 线程"与字符串之间的互转规则。
 *
 * <p>这几样只认 JDK 与 {@code claw4j-core} 的契约，不知道 Slack 怎么验签、怎么调 Web API；
 * 那两件事在子包 {@code channel.slack.client}，界由 {@code ChannelBoundaryTest} 的两条规则守住。
 *
 * <p>与其他通道的实质差别有两处，这个包的形状就是由它们决定的：
 * <ul>
 *   <li>签名覆盖整个报文（不只是时间戳），所以验签必须拿原始字符串，反序列化再序列化一遍就验不过。</li>
 *   <li>"回哪儿"不是某个人，是一个位置（频道加线程），于是多出一个
 *       {@link io.github.claw4j.channel.slack.SlackConversation}。</li>
 * </ul>
 *
 * <p>没有 {@code .sdk} 子包：这一版走 Events API 的 HTTP 回调，不需要厂商 SDK——
 * 验签是一次 HMAC，发消息是一个 POST 一个 JSON。Slack 的官方 Java SDK 面向 Socket Mode
 * 那类由客户端主动连出去推送的形态，与回调编排对不上，本版不做。
 */
package io.github.claw4j.channel.slack;
