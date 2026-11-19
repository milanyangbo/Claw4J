package io.github.claw4j.app;

import io.github.claw4j.agent.session.SessionScopeProperties;
import io.github.claw4j.channel.dingtalk.client.DingtalkProperties;
import io.github.claw4j.channel.feishu.sdk.FeishuChannelProperties;
import io.github.claw4j.channel.http.HttpChannelProperties;
import io.github.claw4j.channel.slack.client.SlackProperties;
import io.github.claw4j.channel.wecom.client.WecomProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 启动期自洽校验：多个人可及，就不能让他们共用一段主会话（ADR-0032）。
 *
 * <p>{@code dm-scope=main} 的语义是一个人的所有私聊并成一段。单用户部署这是便利；一旦这个进程
 * 能被多个人敲到，它就把不同人的上下文并成一段——网页里问到的数字，飞书私聊里的模型也看得见。
 * 这条风险以前只写在注释里，代码不拦；这个类把它变成"起不来"。
 *
 * <p>判据是**所有已启用通道准入名单的并集**，不是逐通道：http 给 alice、飞书给 bob，每条各自
 * 只有一个人，两个人共用主会话照样串话。{@code *}（不设准入）按"人数未知"算，取严的一侧。
 *
 * <p>为什么在 app：agent 不认识 channel（ArchUnit 拦着），这里两边都依赖，是唯一能同时拿到
 * {@link SessionScopeProperties} 与五个通道配置的地方。
 *
 * <p>判"已启用"靠 bean 在不在：通道的配置类只在 {@code claw4j.channel.<名字>.enabled=true} 时注册，
 * 拿不到就是那道门没开、不算入口。
 */
@Component
class SessionBoundaryCheck implements InitializingBean {

    /** 通道准入名单里的通配：谁都能进，等价于"人数未知"。 */
    static final String ANY_USER = "*";

    private final Set<String> admittedUsers;

    private final SessionScopeProperties scopes;

    SessionBoundaryCheck(ObjectProvider<HttpChannelProperties> http,
                         ObjectProvider<FeishuChannelProperties> feishu,
                         ObjectProvider<WecomProperties> wecom,
                         ObjectProvider<DingtalkProperties> dingtalk,
                         ObjectProvider<SlackProperties> slack,
                         SessionScopeProperties scopes) {
        Set<String> union = new LinkedHashSet<>();
        HttpChannelProperties httpProps = http.getIfAvailable();
        if (httpProps != null) {
            union.addAll(httpProps.getUsers());
        }
        FeishuChannelProperties feishuProps = feishu.getIfAvailable();
        if (feishuProps != null) {
            union.addAll(feishuProps.getUsers());
        }
        WecomProperties wecomProps = wecom.getIfAvailable();
        if (wecomProps != null) {
            union.addAll(wecomProps.getUsers());
        }
        DingtalkProperties dingtalkProps = dingtalk.getIfAvailable();
        if (dingtalkProps != null) {
            union.addAll(dingtalkProps.getUsers());
        }
        SlackProperties slackProps = slack.getIfAvailable();
        if (slackProps != null) {
            union.addAll(slackProps.getUsers());
        }
        this.admittedUsers = Set.copyOf(union);
        this.scopes = scopes;
    }

    /**
     * 判定在 {@link #verify(Set, String)} 里；这里只把两半凑到一起。
     *
     * @throws IllegalStateException 多人可及而私聊用的是 {@code main}
     */
    @Override
    public void afterPropertiesSet() {
        verify(admittedUsers, scopes.dmScope());
    }

    /**
     * 判定本身。抽成静态方法是为了不靠容器也能验——这一支用错方向就是串话，得有用例钉住。
     *
     * @param admitted 所有已启用通道准入名单的并集
     * @param dmScope  私聊边界生效值
     * @throws IllegalStateException 多人可及（或含通配）而 {@code dmScope} 是 {@code main}
     */
    static void verify(Set<String> admitted, String dmScope) {
        if (!SessionScopeProperties.DM_MAIN.equals(dmScope) || admitted.isEmpty()) {
            // 不是 main：各有各的分法，不存在"并成一段"。一个人都进不来：谈不上串话。
            return;
        }
        if (admitted.contains(ANY_USER)) {
            throw new IllegalStateException("claw4j.advisor.session.dm-scope=" + SessionScopeProperties.DM_MAIN
                    + "，而某条通道的 users 写了「" + ANY_USER + "」（不设准入）。"
                    + "这等于让不认识的人共用同一段会话上下文：网页里问到的数字，飞书私聊里也看得见。"
                    + "二选一：把 users 收成具体的人，或把 dm-scope 改成 "
                    + SessionScopeProperties.DM_PER_CHANNEL_PEER + "（ADR-0032）。");
        }
        if (admitted.size() > 1) {
            throw new IllegalStateException("claw4j.advisor.session.dm-scope=" + SessionScopeProperties.DM_MAIN
                    + "，但准入名单里有多个人：" + admitted + "。"
                    + "这会把他们的私聊并成同一段会话上下文（网页里问到的数字，飞书私聊里也看得见）。"
                    + "把 dm-scope 改成 " + SessionScopeProperties.DM_PER_CHANNEL_PEER
                    + "，或把 users 收成一个人（ADR-0032）。");
        }
    }
}
