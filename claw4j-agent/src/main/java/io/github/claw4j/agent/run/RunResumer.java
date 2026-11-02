package io.github.claw4j.agent.run;

import java.util.Optional;

/**
 * 审批有了结论之后，把那件停在原地的事接着做下去（P02）。
 *
 * <p><b>它不是 {@code AgentPort} 上的方法</b>：那条口子是"有人对 Agent 说了一句话"，
 * 而这里是"没有人说话，但有一件事可以往下走了"。两者的入参、鉴权来源、返回值都不同——
 * 前者用消息里带的身份，后者用 run 上存的发起人快照。挤进同一个接口的后果是
 * 每个通道实现都要多实现一个它永远不会调的方法。
 *
 * <p><b>续跑的起点不是"从头再来"</b>：交给模型的是这件事的 recap（目标 + 计划 + 最近几步）
 * 加一句结论。用户不需要重新解释背景，这正是挂起-续跑这一整套存在的理由。
 */
public interface RunResumer {

    /**
     * @param runId 哪一件事
     * @param note  回灌给模型的一句话：批了（继续做）或者被拒了（连同理由）。
     *              <b>拒绝也必须回灌</b>，否则模型手里只剩"上次卡住了"，
     *              下一轮大概率把同一个请求再发一次
     * @return 这一轮的回答全文。空表示没跑成——没有这个 run、读不到发起人、
     *         或者它压根不在 suspended。三种都不细分：调用方对它们的动作一样（不再催）
     */
    Optional<String> resume(String runId, String note);
}
