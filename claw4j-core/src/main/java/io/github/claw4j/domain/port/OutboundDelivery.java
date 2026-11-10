package io.github.claw4j.domain.port;

/**
 * 出站投递：把一个结果送到某个通道里的一个地址（ADR-0018）。
 *
 * <p>放在这里而不是 {@code channel.spi}：ADR-0018 原本写的是"与 {@code ChatChannel} 平级"，
 * 但那会撞上机器校验的一条规则——agent 不许认识 {@code io.github.claw4j.channel..}
 * （{@code claw4j-agent} 的 {@code ModelAndToolSelectionTest#agentKnowsNoChannel}）。而挑起
 * 投递的恰恰是 Agent，它才知道一件事什么时候做完。边界规则是机器校验的、先于文档存在，所以
 * 这里按它走：本接口是 core 的契约，实现在各通道模块，接线在 app，依赖方向仍是
 * {@code channel → core ← agent}。
 *
 * <p>与 {@code ChatChannel} 方向相反：那边是"消息怎么进来"，这里是"结果怎么出去"。分开的原因
 * 很具体——进来的能力每个通道都有，出去的未必：HTTP 是请求-响应的，连接早就断了，它没有可推
 * 的地址，所以用轮询；飞书用 IM 的 {@code CreateMessage}，与当前请求毫无耦合，天生能推。合成
 * 一个接口的话，做不出推的通道只能写一个空实现，而"有一个 send 什么都不做"比"这个通道不支持
 * 推"难发现得多。
 *
 * <p>地址是通道内的，不是全局的：{@code channelRef} 是本通道自己认得的写法（飞书是
 * {@code chat_id}，企微是会话 id）。它不是回调地址——回调地址是单点，且要能被外部访问；这里
 * 记的是"回到那个会话去"。一件事可以登记多个地址（跨通道收结果就靠这个），所以"送到哪"是库
 * 里的一张一对多表，不是本接口的一个参数。
 *
 * <p>失败约定是抛，不吞：吞掉的后果是群里没有回答、日志里也没有痕迹，而那两个症状与"根本没跑"
 * 一模一样。由调用方（投递那一段）决定记在哪里、要不要重试——重试策略取决于具体平台的限流
 * 规则，它属于通道自己，不属于这里。
 */
public interface OutboundDelivery {

    /**
     * 通道标识，与审计里记的通道同口径（{@code feishu} / {@code wecom} / …）。
     * 没有实现推的通道不注册本接口的 bean。
     */
    String channel();

    /**
     * 把这段文本送到这个地址。
     *
     * @param channelRef 本通道内的收件地址
     * @param text       要送的结果全文。空文本不该调到这里——"没有内容"由调用方先滤掉
     * @throws RuntimeException 发送失败，由投递那一段决定记录与重试
     */
    void send(String channelRef, String text);
}
