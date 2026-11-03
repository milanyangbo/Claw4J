/**
 * Slack 通道里<b>与平台无关的那一半</b>：一条已经解出来的入站消息、一个把文本推回去的出口、
 * 一个能被 agent 按通道名找到的投递实现，以及一个把"频道 + 线程"压成字符串的规则。
 *
 * <p>分包的理由与另外三个通道一致：上面那几样只认 JDK 与 {@code claw4j-core} 的契约，
 * 不该知道 Slack 怎么验签、怎么调 Web API。那两件事在子包 {@code channel.slack.client} 里，
 * 由 {@code ChannelBoundaryTest} 用两条规则把界守住。
 *
 * <p><b>Slack 与另外三家的协议不是同一类</b>，这一点决定了这个包的形状：
 *
 * <table border="1">
 *   <caption>入站长什么样</caption>
 *   <tr><th>通道</th><th>报文</th><th>签名在哪儿</th><th>会话是什么</th></tr>
 *   <tr><td>飞书</td><td>SDK 长连接推过来的事件对象</td><td>由 SDK 负责，我们不验</td>
 *       <td>{@code chat_id}</td></tr>
 *   <tr><td>企业微信</td><td>加密 XML</td><td>query 里的 {@code msg_signature}（SHA-1）</td>
 *       <td>成员 UserID</td></tr>
 *   <tr><td>钉钉</td><td>明文 JSON</td><td>header 里的 {@code sign}（HMAC-SHA256）</td>
 *       <td>成员 userId</td></tr>
 *   <tr><td>Slack</td><td>明文 JSON</td><td>header 里的 {@code x-slack-signature}
 *       （HMAC-SHA256，<b>覆盖原始报文</b>）</td>
 *       <td><b>频道 + 线程</b></td></tr>
 * </table>
 *
 * <p>最后两列是本包与前三份唯一的实质差别：签名覆盖的是<b>整个报文</b>（不是像钉钉那样只覆盖时间戳），
 * 所以验签必须用<b>原始字符串</b>——反序列化再序列化一遍就验不过；
 * 而"回哪儿"不再是某个人，是一个位置，于是多出一个 {@link io.github.claw4j.channel.slack.SlackConversation}。
 *
 * <p><b>没有 {@code .sdk} 后缀</b>：这一版走 Events API 的 HTTP 回调，不需要厂商 SDK——
 * 验签是一次 HMAC，发消息是"一个 POST 一个 JSON"。
 * Slack 的官方 Java SDK 是给 Socket Mode 那类形态用的，而那个形态本票不做
 * （理由见票面第一节：官方建议生产用 HTTP，且它是"我们连出去推"，与回调编排对不上）。
 */
package io.github.claw4j.channel.slack;
