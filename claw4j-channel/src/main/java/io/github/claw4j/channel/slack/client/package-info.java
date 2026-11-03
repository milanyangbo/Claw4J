/**
 * Slack 通道里<b>"怎么跟平台说话"</b>的那一半：验签、把事件报文压成五个字段、
 * 调 {@code chat.postMessage} 发消息、以及装配。
 *
 * <p>叫 {@code client} 而不是 {@code sdk}，理由与企业微信、钉钉两份一致：这里没有厂商 SDK，
 * 只有一段自己写的 HTTP 客户端。Slack 确实有官方 Java SDK（{@code com.slack.api}），
 * 但它是给 Socket Mode 与大量 Web API 方法用的，而这一版只碰两个接口——
 * 收一个回调、发一条消息。引它换来的是"它什么时候跟进 Spring Boot 4"这类与本事无关的问题。
 *
 * <p>四件事，四份代码，界线很清楚：
 *
 * <ul>
 *   <li>{@link io.github.claw4j.channel.slack.client.SlackSignature}：唯一的密码学动作。
 *       <b>它决定了这个通道安不安全</b>——报文是明文，没有它，公网上的任何人都能把一段话
 *       塞进模型里。注意它覆盖的是<b>整个原始报文</b>，与钉钉只覆盖时间戳不同。</li>
 *   <li>{@link io.github.claw4j.channel.slack.client.SlackMessageTranslator}：报文 → 内核的五个字段，
 *       或者"这条不该答 / 答不了 / 这是握手"。判定顺序是这份代码里最要紧的地方：
 *       {@code bot_id} 与 {@code subtype} 必须先挡，否则 Slack 会把我们自己发的消息再推回来。</li>
 *   <li>{@link io.github.claw4j.channel.slack.client.SlackHttpReplySender}：一次 POST。
 *       判 {@code ok} 而不是判状态码、主动排队避开"同频道 1 秒 1 条"，两件事都写在那份类的注释里。</li>
 *   <li>{@link io.github.claw4j.channel.slack.client.SlackChannelAutoConfiguration}：装配。</li>
 * </ul>
 *
 * <p>适配层（{@code channel.slack}）不许伸进这个包，这个包也不许反向依赖 Agent 与能力——
 * 两条由 {@code ChannelBoundaryTest} 钉住，与另外三个通道同构。
 */
package io.github.claw4j.channel.slack.client;
