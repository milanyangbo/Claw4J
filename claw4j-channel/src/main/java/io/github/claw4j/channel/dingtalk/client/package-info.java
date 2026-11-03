/**
 * 钉钉通道里<b>"怎么跟平台说话"</b>的那一半：验签、把明文 JSON 压成五个字段、
 * 换 token 与发单聊、以及装配。
 *
 * <p>叫 {@code client} 而不是 {@code sdk}，理由与企业微信那份一致：这里没有厂商 SDK，
 * 只有一段自己写的 HTTP 客户端。钉钉确实有官方 SDK（{@code dingtalk-stream-sdk-java}），
 * 但它是给 Stream（WebSocket 长连接）模式用的，而这一版走 HTTP 回调——
 * 换 token 与发消息都只是"一个 POST 一个 JSON"，拖进一个 SDK 换不来任何东西。
 *
 * <p>四件事，四份代码，界线很清楚：
 *
 * <ul>
 *   <li>{@link io.github.claw4j.channel.dingtalk.client.DingtalkSignature}：唯一的密码学动作。
 *       <b>它决定了这个通道安不安全</b>——报文是明文，没有它，公网上的任何人都能把一段话
 *       塞进模型里。</li>
 *   <li>{@link io.github.claw4j.channel.dingtalk.client.DingtalkMessageTranslator}：报文 →
 *       内核的五个字段，或者"这条不该答 / 答不了"。</li>
 *   <li>{@link io.github.claw4j.channel.dingtalk.client.DingtalkHttpReplySender}：换 token 加发消息。
 *       token 缓存、{@code msgParam} 是字符串、限流要看得见，三件事都写在那份类的注释里。</li>
 *   <li>{@link io.github.claw4j.channel.dingtalk.client.DingtalkChannelAutoConfiguration}：装配。</li>
 * </ul>
 *
 * <p>适配层（{@code channel.dingtalk}）不许伸进这个包，这个包也不许反向依赖 Agent 与能力——
 * 两条由 {@code ChannelBoundaryTest} 钉住，与另外两个通道同构。
 */
package io.github.claw4j.channel.dingtalk.client;
