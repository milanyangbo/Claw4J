package io.github.claw4j.app;

import io.github.claw4j.plugin.CoreVersion;
import io.github.claw4j.plugin.PluginContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 启动期的插件版本契约（P16）：核心版本不满足插件声明的区间，就停在这里。
 *
 * <p><b>为什么是 {@link BeanFactoryPostProcessor} 而不是普通 bean</b>：要求是"在插件的 bean 被创建
 * 之前"报出来。普通 bean 做不到——谁能先创建由依赖关系决定，而插件在 {@code @Bean} 方法体里
 * 第一次碰到已被删除的方法时就已经 {@code NoSuchMethodError} 了，"版本不对"那句话永远轮不到。
 * BFPP 跑在单例实例化之前，也早于任何 {@code @Bean} 方法被调用。
 *
 * <p>代价接受得干脆：BFPP 里不能创建 bean，所以这里只读资源、不注入任何东西——
 * {@code beanFactory} 那个参数用不上，也不需要。
 *
 * <p><b>三处失败都是硬失败</b>：契约不成立、核心版本读不到、核心版本读不成版本号。
 * "读不到就当已知"是这类闸最常见的死法：它不会报错，只会在某天以一个 {@code NoSuchMethodError}
 * 出现，而那正是这张票要消灭的形态。
 */
public final class PluginContractGate implements BeanFactoryPostProcessor {

    /** 核心版本由 {@code claw4j-core} 的构建过滤生成，jar 里与 {@code target/classes} 里都有。 */
    static final String CORE_VERSION_RESOURCE = "META-INF/claw4j-core.properties";

    private static final String CORE_VERSION_KEY = "version";

    private static final String DESCRIPTORS = "classpath*:" + PluginContract.FILE;

    private static final Logger LOG = LoggerFactory.getLogger(PluginContractGate.class);

    private final ResourcePatternResolver resolver;

    public PluginContractGate() {
        this(new PathMatchingResourcePatternResolver());
    }

    PluginContractGate(ResourcePatternResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        String coreText = coreVersionText();
        CoreVersion core = parseCore(coreText);
        Map<String, Properties> descriptors = descriptors();
        List<PluginContract.Violation> violations = PluginContract.checkAll(core, coreText, descriptors);
        if (!violations.isEmpty()) {
            throw new IllegalStateException(report(coreText, violations));
        }
        LOG.info("插件版本契约成立：{} 个插件声明都与核心 {} 匹配", descriptors.size(), coreText);
    }

    /**
     * 原样读出来，不先解析。报错要照抄构件上的版本号：{@link CoreVersion} 有意丢掉限定符，
     * 于是 {@code 0.1.0-SNAPSHOT} 会被说成 {@code 0.1.0}，而运维会先去核对"我到底部署了什么"。
     */
    private String coreVersionText() {
        Resource resource = resolver.getResource("classpath:" + CORE_VERSION_RESOURCE);
        if (!resource.exists()) {
            throw new IllegalStateException("读不到核心版本：classpath 上没有 " + CORE_VERSION_RESOURCE
                    + "。它由 claw4j-core 的构建过滤生成，缺了通常意味着这个核心 jar 不是用本仓库的构建产出的。");
        }
        String version = load(resource).getProperty(CORE_VERSION_KEY);
        if (version == null || version.isBlank()) {
            throw new IllegalStateException(CORE_VERSION_RESOURCE + " 里没有 " + CORE_VERSION_KEY + "："
                    + resource.getDescription());
        }
        return version.strip();
    }

    private static CoreVersion parseCore(String text) {
        try {
            return CoreVersion.parse(text);
        } catch (IllegalArgumentException broken) {
            throw new IllegalStateException("核心版本读不成版本号：" + text
                    + "（" + CORE_VERSION_RESOURCE + "）", broken);
        }
    }

    private Map<String, Properties> descriptors() {
        Resource[] found;
        try {
            found = resolver.getResources(DESCRIPTORS);
        } catch (IOException broken) {
            throw new IllegalStateException("扫描插件契约文件失败：" + DESCRIPTORS, broken);
        }
        Map<String, Properties> bySource = new LinkedHashMap<>();
        for (Resource resource : found) {
            bySource.put(source(resource), load(resource));
        }
        return bySource;
    }

    /** 报错里要能定位到具体那个 jar：插件常常有好几个，只说名字找不到文件。 */
    private static String source(Resource resource) {
        try {
            return resource.getURI().toString();
        } catch (IOException broken) {
            return resource.getDescription();
        }
    }

    /**
     * 按 UTF-8 读。<b>不是 {@code Properties.load(InputStream)}</b>：那个方法按 ISO-8859-1 解码，
     * 插件写个中文名就会变成乱码——而乱码只会出现在报错里，偏偏是出事时才看见。
     */
    private static Properties load(Resource resource) {
        Properties properties = new Properties();
        try (InputStream in = resource.getInputStream()) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException broken) {
            throw new IllegalStateException("读不了 " + resource.getDescription(), broken);
        }
        return properties;
    }

    private static String report(String coreText, List<PluginContract.Violation> violations) {
        String line = System.lineSeparator();
        StringBuilder text = new StringBuilder("插件与核心的版本契约不成立（当前核心 ").append(coreText).append("）：");
        for (PluginContract.Violation violation : violations) {
            text.append(line).append("  - ").append(violation.message());
        }
        return text.append(line)
                .append("插件与核心必须一起升级：换一个满足区间的插件，或者用回它要求的那个核心版本。")
                .append("这里没有跳过校验的开关。")
                .toString();
    }
}
