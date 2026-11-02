package io.github.claw4j.domain.port;

import io.github.claw4j.domain.principal.Principal;

import java.util.Optional;

/**
 * 查一件事做到哪儿了（ADR-0018）：轮询那一端唯一需要的东西。
 *
 * <p><b>为什么要有这个口子</b>：结果不再只活在"那一次请求"里。
 * 定时任务跑完时没有人在线等着看答案，审批放行之后的结果要给发起审批的人，
 * 子 Agent 跑得久、父那一轮早答完了——三种情况下"谁问的就回给谁"都送不出去。
 * 于是结果必须能<b>按 id 查</b>，而这个口子在 core 里，
 * 通道才能在不依赖 Agent 的前提下把它挂成 {@code GET /api/runs/{id}}。
 *
 * <p><b>认的是人，不是通道</b>：判据是 {@code owner}，不是"这条请求从哪个会话来"。
 * 在飞书里开的头，可以在 HTTP 上查到同一件事——这正是"run 绑 principal，不绑通道"那句话
 * 落到接口上的样子。换通道不该换一件事。
 *
 * <p>实现在 Agent 模块（那里才有 {@code claw4j_run}），装配由 app 完成。
 */
public interface RunQuery {

    /**
     * 读一件事的当前形状。
     *
     * @param runId  要查的那件事
     * @param reader 查的人。<b>不属于他的 run 与"没有这个 run"返回同一个答案</b>——
     *               分成 403 与 404 等于替对方确认"这个 id 存在，只是不是你的"，
     *               而 run id 是唯一一个能凭空试出别人事情的入口
     * @return 空表示查不到（没有、不是你的、或压根没装配 run 存储）
     */
    Optional<RunView> find(String runId, Principal reader);
}
