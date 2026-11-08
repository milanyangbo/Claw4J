package io.github.claw4j.channel.feishu.sdk;

/**
 * 飞书长连接的**可注入形态**。存在的唯一理由：让"第一次抛 IOException、第二次成功"这件事能被测到。
 *
 * <p>它只包两个动作，与 {@code com.lark.oapi.ws.Client} 的两个方法一一对应：
 * {@code start()}（异步开始握手）与 {@code awaitReady(超时)}（等它结果）。
 * 这两个方法就是"连不上"唯一能冒出来的地方——不把它们抽出来，重连逻辑就只能人工拔网线验。
 *
 * <p><b>为什么不把 {@code Client} 整个包一层</b>：包了就会忍不住往里加方法（发消息、读事件、关连接），
 * 于是测试替身要跟着实现一堆与"重连"无关的东西。这里只留"建连"这一件事。
 *
 * <p>关闭不抛检查异常：失败之后一定要关，而"关的时候又错了"不该盖住原来的错误。
 */
public interface FeishuConnection extends AutoCloseable {

    /** 开始建连。SDK 的这一步是异步的，失败要等 {@link #awaitReady} 才看得见。 */
    void start() throws Exception;

    /**
     * 等到握手完成，或者等到超时。
     *
     * <p>SDK 在超时抛的是 {@code ServerUnreachableException}
     * （"websocket handshake did not complete within %dms"），不是 {@code TimeoutException}——
     * 分类函数按前者判，见 {@link FeishuConnectionFailures}。
     */
    void awaitReady(long timeoutMillis) throws Exception;

    @Override
    void close();
}
