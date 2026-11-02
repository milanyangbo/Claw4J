package io.github.claw4j.domain.port;

/**
 * 审批卡片：把一个待批请求推到人眼前（P03）。
 *
 * <p><b>为什么卡片是"端口"而不是某个通道的私事</b>：一次审批要被人看见，而"看见"的样子
 * 由通道决定（飞书是交互卡片，HTTP 是一个 JSON 接口，企微是另一种卡片）。判定那一段
 * 只在 {@link ApprovalDecider} 里写一次，{@code channel → core ← agent} 这条边
 * （ADR-0010）决定了它不能认识任何具体通道，于是"谁来画这张卡片"必须是一个契约。
 *
 * <p><b>只有"推"这一个动作，没有"改"</b>：点完按钮之后那张卡片怎么变，是通道自己的事——
 * 飞书用点击事件的响应体就地换掉卡片（同一条消息，读者不会看到两条互相矛盾的消息），
 * 那个机制只有通道认识，把"改卡片"也放进契约等于让每个实现都去猜别人的回调长什么样。
 *
 * <p><b>卡片不是审批的入口，它只是一个信封</b>：真正的决定仍然走
 * {@link ApprovalDecider#decide}，与 HTTP 接口调的是同一个方法。卡片上的按钮只携带
 * "哪一条 + 批还是拒"，谁点的由通道自己认出来后交给同一处。这样"卡片能批"与"接口能批"
 * 不会长出两套规矩——尤其是"不能批自己的请求"那一条，写在入口上的话，第二个入口必然漏。
 *
 * <p><b>一个通道一个实现</b>：{@link #channel()} 与 {@code agent_run_delivery.channel}
 * 同口径，投递时按这个值把卡片交给对应的实现。没有实现的通道（HTTP）不推卡片，
 * 它那边的入口是接口——两种形态并存是有意的，见 {@code ApprovalDecider} 的说明。
 *
 * <p><b>推不出去不是审批失败</b>：登记与挂起早就落库了，卡片只是"让人看见"的尝试。
 * 实现可以抛（网络、限频、凭据过期都算），由调用方决定记日志还是重试——
 * 但那一轮回答不能因为卡片没发出去而变成 500，它已经带着审批编号回到对话里了。
 */
public interface ApprovalCards {

    /** 与 {@code agent_run_delivery.channel} 同口径的通道标识。 */
    String channel();

    /**
     * 把一个待批请求推给某个会话。
     *
     * @param channelRef 通道内的地址（飞书是 {@code chat_id}）
     * @param card       这条请求的当前样子。实现只负责把它画出来，不改里面的判断
     */
    void announce(String channelRef, ApprovalDecider.Pending card);
}
