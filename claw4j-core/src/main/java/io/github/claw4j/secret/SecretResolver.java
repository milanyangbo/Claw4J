package io.github.claw4j.secret;

import java.util.Locale;
import java.util.Optional;

/**
 * 把句柄名换成真值（P08）。
 *
 * <p>它是真值唯一的出口：配置里只写 {@code secret:<名字>}，真值在"发请求 / 起子进程 /
 * 写 http header"那一刻才被问出来。配置对象手里从头到尾只有名字，于是"这次调用用的是哪把钥匙"
 * 有答案，"任何一个注入进来的 bean 都能随手取到 key"没有答案。
 *
 * <p>名字与环境变量名是两个东西：真值只能来自部署环境（环境变量，或由
 * {@code spring.config.import} 读进来的 {@code .env}），这一点改不掉；能改的是"配置里写什么"。
 * 句柄名是环境变量名的小写写法，换算规则只在本接口的 {@link #environmentName} 一处，于是
 * "句柄 ⟷ 变量"不会出现两套约定。名字与变量不是同一个字符串，也顺带挡住"照着配置抄一个名字去
 * {@code System.getenv}"这种顺手。
 *
 * <p>不缓存：{@code lookup} 每次都问到源头。凭据轮换正是这类机制存在的理由之一，缓存把那个理由
 * 削弱了；代价是每次出口多一次 Map 查找，与本票要解决的问题不在一个量级。
 *
 * <p>它是接口而不是类：测试要能给出"这个句柄不存在"而不依赖本机环境。生产实现见
 * {@code io.github.claw4j.capability.secret.SpringSecretResolver}（放在 capability 是因为
 * {@code claw4j-core} 不许有 Spring，而 agent 依赖 capability）。
 */
public interface SecretResolver {

    /**
     * 问一次真值。
     *
     * @param name 句柄名，已经过 {@link SecretValue} 的形状校验
     * @return 有值则返回，没有或为空则返回空——"配了句柄却没给真值"由调用方决定怎么报
     */
    Optional<String> lookup(String name);

    /**
     * 问一次真值，没有就失败。
     *
     * <p>报错只说句柄名与环境变量名，不回显值、不回显片段：一段失败日志要能贴进工单。
     *
     * @param name  句柄名
     * @param where 配置项的全名，用来回答"是哪一行配错了"
     * @return 真值
     * @throws MissingSecretException 该句柄在环境里不存在或为空
     */
    default String require(String name, String where) {
        return lookup(name).orElseThrow(() -> new MissingSecretException(name, where));
    }

    /**
     * 句柄名 → 环境变量名：转大写，{@code .} 与 {@code -} 换成 {@code _}。
     *
     * <p>写成静态方法而不是各自约一遍：这条规则被报错消息、文档与测试同时引用，两处各写一份必然
     * 改一处漏一处。
     *
     * @param name 句柄名
     * @return 对应的环境变量名
     */
    static String environmentName(String name) {
        return name.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}
