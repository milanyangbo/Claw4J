package io.github.claw4j.channel.dingtalk;

import io.github.claw4j.channel.kernel.ReplySender;

/**
 * 把一段文本推给某个钉钉成员。
 *
 * <p>"会话"在钉钉里是一个企业内 {@code userId}（即入站事件里的 {@code senderStaffId}）。
 * 这一版只做单聊，推送走 {@code POST /v1.0/robot/oToMessages/batchSend}。
 *
 * <p>实现要自己管 access_token：钉钉的新版接口都靠它，有效期 7200 秒。与企业微信有两点
 * 必须区别对待，照抄那边的代码会写出一段死的逻辑：
 *
 * <ol>
 *   <li>有效期内重复获取会返回同一张，并自动续期。所以"平台先一步废掉旧 token、
 *       本地判没过期于是重发同一张"这种重试不会发生——企业微信那段 {@code 42001} 重试逻辑
 *       搬过来永远不触发。</li>
 *   <li>真正会遇到的是限流：{@code send.too.fast} / {@code send.byToken.tooFast}，
 *       以及按人算的 {@code flowControlledStaffIdList}。它们要能被看见（WARN 加抛出），
 *       而不是被吞成"发出去了"。</li>
 * </ol>
 *
 * <p>失败要抛，网络错误、{@code code} 非空、限流都算：调度层要能在日志里记下"这一条没发出去"。
 */
public interface DingtalkReplySender extends ReplySender {

    /**
     * 发一段文本给一个成员。
     *
     * @param userId 企业内 userId，与入站事件里的 {@code senderStaffId} 同源
     * @param text 已经切好的一段文本，不会为空
     * @throws IllegalStateException 发送失败，含换不到 token 与限流
     */
    @Override
    void send(String userId, String text);
}
