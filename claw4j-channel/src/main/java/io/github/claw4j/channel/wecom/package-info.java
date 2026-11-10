/**
 * 企业微信通道里与平台无关的那一半：一条已经解出来的入站消息、一个把文本推回去的出口、
 * 一个能被 agent 按通道名找到的投递实现。
 *
 * <p>分包的理由与 {@code channel.feishu} 一致：上面那三样只认 JDK 与 {@code claw4j-core} 的契约，
 * 不该知道企业微信怎么加解密、怎么换 access_token。那两件事在子包 {@code channel.wecom.client} 里，
 * 由 {@code ChannelBoundaryTest} 用两条规则把界守住（适配层不依赖外部实现，
 * 也不反过来伸手去拿自己那个子包）。
 *
 * <p>企业微信没有官方 Java SDK（ADR-0009）：社区包 {@code weixin-java-cp} 把加解密、换 token、
 * 发消息都包了一层，但为了一个回调端点和一次 {@code POST} 拖进一个第三方依赖，代价比收益大。
 * 加解密是 AES-256-CBC 加一个 SHA-1 签名，两百行以内写得完，而且写出来的每一行都能被单测钉住。
 * 这也是本包没有 {@code .sdk} 后缀的原因：那里没有厂商 SDK 绑定层，只有自己写的一段 HTTP 客户端。
 */
package io.github.claw4j.channel.wecom;
