/**
 * 飞书的 SDK 绑定层：把 {@code oapi-sdk} 的对象翻译成 {@code channel.feishu} 那一层认识的类型。
 *
 * <p>分在子包不是洁癖：{@code channel.feishu} 要能在没有 SDK、没有凭据的默认构建里被测试，
 * 所以"认识 SDK"这件事必须集中在能被 ArchUnit 一包排除的地方（见 {@code ChannelBoundaryTest}）。
 *
 * <p>这一层**不写单测**。它的全部内容是"把事件对象翻成几个字符串"与"发一条消息"，
 * 两者都只有在真凭据下才有意义：假事件能证明我们读对了 getter，证明不了飞书真的会那样推。
 * 它由 {@code -Pfeishu-live} 下的真链接用例覆盖，覆盖不到的部分记在
 * {@code docs/agents/guardrails.md}。
 */
package io.github.claw4j.channel.feishu.sdk;
