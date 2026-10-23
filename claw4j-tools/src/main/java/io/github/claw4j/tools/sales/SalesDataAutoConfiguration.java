package io.github.claw4j.tools.sales;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
// Boot 4 把自动配置按模块重排了包名：jdbc 相关的不再在 autoconfigure 下，
// 而在 org.springframework.boot.jdbc.autoconfigure。
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 业务数据的 JDBC 访问装配。跟 {@code ToolsAutoConfiguration} 分开是因为两者的
 * 前置条件不同：工具回调只要有类就行，这里要等 {@code JdbcClient} 就位，
 * 而没有数据源时（比如只跑 mock 演示）它根本不该被创建。
 *
 * <p>只暴露 {@link SalesDataRepository} 这一个查询入口，不暴露 {@code JdbcClient}
 * 本身：后者一旦放出去，"随便写 SQL"就有了正当理由，Text-to-SQL 那条路会被绕开。
 * 05 号票要加查询就加在这个类里面。
 */
@AutoConfiguration(after = JdbcClientAutoConfiguration.class)
public class SalesDataAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SalesDataRepository.class)
    public SalesDataRepository salesDataRepository(JdbcClient jdbc) {
        return new SalesDataRepository(jdbc);
    }
}
