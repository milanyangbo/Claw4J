package io.github.claw4j.agent;

import io.github.claw4j.domain.principal.ToolProfile;
import io.github.claw4j.domain.principal.ToolTier;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 工具策略：这个部署允许到哪一档，外加按工具的 allow / deny（P09）。
 *
 * <p><b>默认必须是 {@code full} 加两张空表</b>：升级一次就少一批工具，是这类开关最容易付的代价，
 * 而"少了哪个"通常要等到某天有人问起"它怎么不会查数了"才发现。
 *
 * <p>三个分量各自解决一件事，合并不了：
 * <ul>
 *   <li>{@code profile} 是**部署决定**——"这套部署只做消息、不许跑代码"，改一次要重启；</li>
 *   <li>{@code allow} 是白名单，只能在档位之内继续收窄；</li>
 *   <li>{@code deny} 是排除，用来去掉档位里那几个不该出现在这个场景的。</li>
 * </ul>
 * 想让 allow 放宽档位是不行的：那样档位就不再是安全默认，而是一句"我大致开了这些"。
 *
 * <p>生效点在清单组装（{@code AgentAutoConfiguration#collectToolCallbacks}），不在执行前，
 * 于是被挡掉的工具检索也检索不到——模型不会先计划再撞墙。
 *
 * @param profile 允许到哪一档，缺省 {@link ToolTier#FULL}
 * @param allow   白名单；空表示不额外收窄
 * @param deny    黑名单；在档位与白名单之后生效
 */
@ConfigurationProperties("claw4j.tools")
public record ToolsProperties(ToolTier profile, List<String> allow, List<String> deny) {

    public ToolsProperties {
        profile = profile == null ? ToolTier.FULL : profile;
        allow = allow == null ? List.of() : List.copyOf(allow);
        deny = deny == null ? List.of() : List.copyOf(deny);
    }

    /** 全默认：全部档位、不收窄、不排除。与 {@link ToolProfile#unrestricted()} 是同一件事。 */
    public static ToolsProperties defaults() {
        return new ToolsProperties(null, null, null);
    }

    /**
     * 变成领域对象。
     *
     * <p>用 {@link LinkedHashSet} 而不是 {@code Set.copyOf}：后者不保证顺序，
     * 而启动日志里"挡掉了哪几个"按配置顺序读起来更省事，排查时也更容易对上 yml。
     */
    public ToolProfile toProfile() {
        return new ToolProfile(profile, new LinkedHashSet<>(allow), new LinkedHashSet<>(deny));
    }
}
