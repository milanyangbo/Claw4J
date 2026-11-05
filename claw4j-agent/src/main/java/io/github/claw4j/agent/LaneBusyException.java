package io.github.claw4j.agent;

/**
 * 这段对话还在处理上一句，等到上限也没轮到（ADR-0026 决定二）。
 *
 * <p><b>它是一个"要被翻译成人话"的异常，不是 500</b>：调用方拿到它之后要回
 * {@link ConversationLane#BUSY_REPLY}，而不是让堆栈冒到通道层。已经 ACK 的消息
 * 收不到任何回音，用户只会以为机器人坏了——那比"有点忙"糟得多。
 *
 * <p><b>刻意不带对话地址</b>：地址里有 {@code userId}，而异常消息是最容易被整段打进
 * 日志、甚至回给调用方的那种字符串。需要知道是哪条对话时，看调用点那行 debug 日志。
 */
public class LaneBusyException extends RuntimeException {

    public LaneBusyException() {
        super("这段对话正在处理上一句，等到上限也没轮到");
    }
}
