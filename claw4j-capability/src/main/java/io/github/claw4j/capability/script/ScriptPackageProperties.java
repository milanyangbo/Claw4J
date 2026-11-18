package io.github.claw4j.capability.script;

import io.github.claw4j.domain.principal.ToolFilter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 脚本层的配置：{@code claw4j.scripts} 这一段。
 *
 * <p>做成一个绑定对象而不是几个 {@code @Value}：{@code tool-filter} 是嵌套结构
 * （{@code include} / {@code exclude} 两个清单），{@code @Value} 表达不了它，
 * 而给它单开一个 {@code @ConfigurationProperties} 又会在同一段前缀上出现两套读法——
 * 下次有人加字段时不知道该写在哪一边。
 *
 * @param directory  脚本包目录。配了就必须存在，不存在即启动失败（{@link ScriptPackageCatalog#load}）
 * @param toolFilter 哪些包**存在**（票 07）：被排掉的包不进清单、不进权限与画像登记
 */
@ConfigurationProperties("claw4j.scripts")
public record ScriptPackageProperties(String directory,
                                      @DefaultValue ToolFilter toolFilter) {
}
