package io.github.claw4j.agent.rag;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用两条正则做保护：一条决定"什么问题索性别改"，一条决定"改写里必须原样保留什么"。
 *
 * <p><b>这个类里没有任何词表</b>。中文的金额单位（元/天/个工作日）、型号写法（{@code SKU-2024}、
 * {@code A2}）全部从配置来。原因很直接：一个知识库是售后合同，另一个是医疗指南或英文产品手册，
 * 单位表不可能共用一份；把任何一份写进代码，它就只对那一个领域成立，而这个类会被所有领域引用。
 *
 * <p>两条都可以为空，空 = 不做那一道检查。两条都空等于裸改写——仍然有超时、清洗、失败回落，
 * 只是不再拦截任何问题。
 *
 * <p><b>正则由装配处编译</b>，不是在这里 {@code Pattern.compile} 字符串：语法错在启动时就抛，
 * 不会等到第一次提问才暴露。语义写错（比如漏了"小时"这个单位）没法自动检测，那属于配置内容，
 * 只能靠对照实验发现——这条代价必须认下来，它是"配置优先"的必然结果。
 */
public class PatternGuard implements RewriteGuard {

    /** 命中就跳过改写的问题特征；null = 从不跳过。 */
    private final Pattern skip;

    /** 改写结果必须原样保留的片段特征；null = 不做保留校验。 */
    private final Pattern keep;

    /**
     * @param skip 跳过用的正则，可以为 null
     * @param keep 保留校验用的正则，可以为 null
     */
    public PatternGuard(Pattern skip, Pattern keep) {
        this.skip = skip;
        this.keep = keep;
    }

    @Override
    public boolean shouldSkip(String question) {
        return skip != null && skip.matcher(question).find();
    }

    @Override
    public boolean keepsAll(String question, String rewritten) {
        if (keep == null) {
            return true;
        }
        String haystack = rewritten.toLowerCase(Locale.ROOT);
        Matcher matcher = keep.matcher(question);
        while (matcher.find()) {
            if (!haystack.contains(matcher.group().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }
}
