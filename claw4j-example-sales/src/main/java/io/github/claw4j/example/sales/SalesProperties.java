package io.github.claw4j.example.sales;

import io.github.claw4j.domain.principal.ToolFilter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 示例插件的配置：{@code claw4j.example.sales} 这一段。
 *
 * <p>{@code enabled} 不在这里：它由 {@code @ConditionalOnProperty} 读，绑过来只会让"这个插件
 * 在不在"出现两个答案。这里只放"在的时候提供哪些工具"。
 *
 * @param toolFilter 哪些工具**存在**（票 07）。排掉 {@code querySales} 之后，清单里没有它，
 *                   权限与画像登记也一起没有——{@code allow} 加不回来
 */
@ConfigurationProperties("claw4j.example.sales")
public record SalesProperties(@DefaultValue ToolFilter toolFilter) {
}
