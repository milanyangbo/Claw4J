/**
 * Slack 通道中"怎么跟平台说话"的那一半：验签、把事件报文压成内核字段、
 * 调 {@code chat.postMessage} 发消息、以及装配。
 *
 * <p>叫 {@code client} 而不是 {@code sdk}：这里没有厂商 SDK，只有一段自写的 HTTP 客户端。
 * Slack 确实有官方 Java SDK（{@code com.slack.api}），它面向 Socket Mode 与整套 Web API，
 * 而这一版只碰两个接口——收一个回调、发一条消息，引它换来的是"它什么时候跟进 Spring Boot 4"
 * 这类与本事无关的维护问题。
 *
 * <p>四份代码，界线清楚：
 * <ul>
 *   <li>{@link io.github.claw4j.channel.slack.client.SlackSignature}：唯一的密码学动作，
 *       也是这个通道唯一的门。报文是明文，没有它，公网上的任何人都能把一段话塞进模型。
 *       它覆盖整个原始报文，与只覆盖时间戳的方案不同。</li>
 *   <li>{@link io.github.claw4j.channel.slack.client.SlackMessageTranslator}：报文到内核的五个字段，
 *       或者"这条不该答／答不了／这是握手"。判定顺序是这份代码最要紧的地方：
 *       {@code bot_id} 与 {@code subtype} 必须先挡，否则 Slack 会把应用自己发的消息再推回来。</li>
 *   <li>{@link io.github.claw4j.channel.slack.client.SlackHttpReplySender}：一次 POST。
 *       判 {@code ok} 而不判状态码，并主动排队避开"同频道每秒 1 条"。</li>
 *   <li>{@link io.github.claw4j.channel.slack.client.SlackChannelAutoConfiguration}：装配。</li>
 * </ul>
 *
 * <p>适配层（{@code channel.slack}）不许伸进这个包，这个包也不许反向依赖 Agent 与能力，
 * 两条由 {@code ChannelBoundaryTest} 钉住。
 */
package io.github.claw4j.channel.slack.client;
