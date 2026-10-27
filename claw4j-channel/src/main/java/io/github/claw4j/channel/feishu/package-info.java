/**
 * 飞书通道：M2 的真实 IM 通道，走长连接（SDK 主动外连，不需要公网端点）。
 *
 * <p>包内按"认不认识飞书 SDK"分两层，这条线决定哪部分能进默认构建：
 * <ul>
 *   <li>本包 —— 入站调度：去重 → 立刻 ACK → 异步跑 Agent → 回推。只有 JDK 类型，
 *       所以能在没有凭据、没有网络的默认构建里用"假发送 + 可控 executor"断言
 *       "入站不等 Agent"。</li>
 *   <li>{@code channel.feishu.sdk} —— SDK 绑定：长连接回调进来、把 SDK 的事件对象翻译成入站事件；
 *       回推接口的实现调飞书消息 API。这一层只有在真凭据下才有意义，靠"带凭据起一次应用"验。</li>
 * </ul>
 *
 * <p>分界由 ArchUnit 钉住（见 {@code ChannelBoundaryTest}）：本包不许出现 {@code com.larksuite}
 * 的任何类型。理由不是洁癖——这一层要能被默认构建测到，就得先做到"不需要 SDK 也能编译、能跑"。
 */
package io.github.claw4j.channel.feishu;
