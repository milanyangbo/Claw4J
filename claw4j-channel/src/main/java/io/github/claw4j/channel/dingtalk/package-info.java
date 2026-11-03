/**
 * 钉钉通道里<b>与平台无关的那一半</b>：一条已经解出来的入站消息、一个把文本推回去的出口、
 * 一个能被 agent 按通道名找到的投递实现。
 *
 * <p>分包的理由与另外两个通道完全一致：上面那三样只认 JDK 与 {@code claw4j-core} 的契约，
 * 不该知道钉钉怎么验签、怎么换 token。那两件事在子包 {@code channel.dingtalk.client} 里，
 * 由 {@code ChannelBoundaryTest} 用两条规则把界守住。
 *
 * <p><b>钉钉与另外两家的协议不是同一类，这一点决定了这个包的形状</b>：
 *
 * <table border="1">
 *   <caption>入站长什么样</caption>
 *   <tr><th>通道</th><th>报文</th><th>签名在哪儿</th></tr>
 *   <tr><td>飞书</td><td>SDK 长连接推过来的事件对象</td><td>由 SDK 负责，我们不验</td></tr>
 *   <tr><td>企业微信</td><td>加密 XML</td><td>query 里的 {@code msg_signature}（SHA-1）</td></tr>
 *   <tr><td>钉钉</td><td><b>明文 JSON</b></td><td><b>header 里的 {@code sign}</b>（HMAC-SHA256）</td></tr>
 * </table>
 *
 * <p>所以这里没有"加解密"这一层可复用：钉钉那一侧唯一的密码学动作是验签，五秒钟一遍
 * HMAC，写完就没了。也正因为报文是明文，<b>验签就是唯一的门</b>——漏了它等于把一个
 * 公网端点直接交给模型。
 *
 * <p><b>没有 {@code .sdk} 后缀</b>，理由与企业微信那份一样、与飞书那份相反：钉钉不是没有 SDK
 * （{@code dingtalk-stream-sdk-java} 是官方的），而是这一版走 HTTP 回调，用不上它；
 * 换 token 与发消息都只是"一个 POST 一个 JSON"。为这两件事拖进一个 SDK，
 * 换来的是"它什么时候跟进 Spring Boot 4"这类与本事无关的维护问题（ADR-0009 对飞书已经定过：
 * 长连接只在有 SDK 时才有意义，而这里连长连接都不做）。
 */
package io.github.claw4j.channel.dingtalk;
