/**
 * 钉钉通道中"怎么跟平台说话"的那一半：验签、把明文 JSON 压成内核字段、换 token 与发单聊、以及装配。
 *
 * <p>叫 {@code client} 而不是 {@code sdk}：这里没有厂商 SDK，只有一段自写的 HTTP 客户端。
 * 钉钉的官方 SDK 面向 Stream（WebSocket 长连接）模式，而这一版走 HTTP 回调——
 * 换 token 与发消息都只是一个 POST 一个 JSON，拖进一个 SDK 换不来任何东西（ADR-0009:16）。
 *
 * <p>四份代码，界线清楚：
 * <ul>
 *   <li>{@link io.github.claw4j.channel.dingtalk.client.DingtalkSignature}：唯一的密码学动作，
 *       也是这个通道唯一的门。报文是明文，没有它，公网上的任何人都能把一段话塞进模型。</li>
 *   <li>{@link io.github.claw4j.channel.dingtalk.client.DingtalkMessageTranslator}：报文到
 *       内核的五个字段，或者"这条不该答／答不了"。</li>
 *   <li>{@link io.github.claw4j.channel.dingtalk.client.DingtalkHttpReplySender}：换 token 加发消息。
 *       token 缓存、{@code msgParam} 是字符串、限流要看得见，三件事写在那份类的注释里。</li>
 *   <li>{@link io.github.claw4j.channel.dingtalk.client.DingtalkChannelAutoConfiguration}：装配。</li>
 * </ul>
 *
 * <p>适配层（{@code channel.dingtalk}）不许伸进这个包，这个包也不许反向依赖 Agent 与能力，
 * 两条由 {@code ChannelBoundaryTest} 钉住。
 */
package io.github.claw4j.channel.dingtalk.client;
