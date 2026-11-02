/**
 * 企业微信通道里<b>与具体协议有关的那一半</b>：回调的签名与加解密、XML 的翻译、
 * 换 access_token、发应用消息、以及那个被装配进来的 HTTP 端点。
 *
 * <p>这里出现的东西在 {@code channel.wecom} 里都被刻意排除了：那边的三个类型只认
 * JDK 与 {@code claw4j-core} 的契约，所以调度层的用例不需要凭据、不需要网络。
 *
 * <p>三处值得先说清楚，因为它们都是"照文档抄一遍就错"的地方：
 *
 * <ul>
 *   <li><b>签名不是 HMAC</b>：把 {@code token / timestamp / nonce / Encrypt} 四个字符串
 *       按字典序排好直接拼起来做 SHA-1，没有密钥参与。它证明的是"这条回调知道 token"，
 *       不提供任何保密性——保密靠下面那条 AES。</li>
 *   <li><b>解密用的密钥是从 EncodingAESKey 派生的</b>：Base64 之后 32 字节，
 *       前 16 字节同时当 IV（所以 IV 是固定的，这不是疏漏，是协议本身如此）。
 *       明文布局是 {@code 随机 16 字节 + 4 字节长度 + 正文 + receiveid}，
 *       最后那段 receiveid 必须等于本企业的 corpid——不校验它，"别家企业的回调
 *       发给这个地址"也能解出一段像模像样的消息。</li>
 *   <li><b>被动回复不做</b>：安全模式下回一段文本也要现加密一段 XML，而回调只要五秒。
 *       所以这里一律回空串（表示"收到了，不回复"），答案走主动推送——
 *       代价是用户在那十几秒里看不到任何动静，于是补了一句回执（见
 *       {@code WecomChannel#RECEIPT_REPLY}）。</li>
 * </ul>
 */
package io.github.claw4j.channel.wecom.client;
