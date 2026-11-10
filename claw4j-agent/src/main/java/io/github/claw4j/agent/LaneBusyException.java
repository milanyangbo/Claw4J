package io.github.claw4j.agent;

/**
 * 等待上限内没拿到对话 lane 时抛出（ADR-0026 决定二）。
 *
 * <p>调用方要把它翻译成 {@link ConversationLane#BUSY_REPLY}，不能让堆栈冒到通道层。
 * 已经 ACK 的消息如果没有任何回音，用户只能认为机器人坏了。
 *
 * <p>异常消息刻意不带对话地址：地址含 {@code userId}，而异常消息是最容易被整段打进日志、
 * 甚至回给调用方的那类字符串。要定位是哪条对话，看调用点的 debug 日志。
 */
public class LaneBusyException extends RuntimeException {

    /**
     * 消息只描述「忙」这一个事实，不带对话地址。
     */
    public LaneBusyException() {
        super("这段对话正在处理上一句，等到上限也没轮到");
    }
}
