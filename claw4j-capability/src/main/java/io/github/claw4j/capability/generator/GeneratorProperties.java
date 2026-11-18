package io.github.claw4j.capability.generator;

import io.github.claw4j.domain.principal.ToolFilter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 生成器这一层的配置：{@code claw4j.generator} 这一段。
 *
 * <p>从几个 {@code @Value} 换成一个绑定对象，是为了 {@code tool-filter}（票 07）：
 * 它是嵌套结构，{@code @Value} 表达不了，而同一段前缀上放两套读法（{@code @Value} +
 * {@code @ConfigurationProperties}）会让下一个加字段的人不知道该写哪边。
 *
 * @param stagingDirectory   暂存区。配了才有这一层，没有"默认目录"——它会写文件
 * @param reviewDirectory    人审目录。空表示落在暂存区旁边
 * @param publishedDirectory 发布目录。空表示落在暂存区旁边
 * @param auditFile          审计文件。空表示落在暂存区旁边
 * @param toolFilter         这个工具**存不存在**：排掉它，模型就看不到"长新能力"这条路
 */
@ConfigurationProperties("claw4j.generator")
public record GeneratorProperties(String stagingDirectory,
                                  String reviewDirectory,
                                  String publishedDirectory,
                                  String auditFile,
                                  @DefaultValue ToolFilter toolFilter) {
}
