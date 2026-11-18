package io.github.claw4j.capability.exec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * exec 三段式的纯函数部分：预设展开、反解、要审批的判定、命令分段、pattern 生成。
 *
 * <p>不碰进程、不碰库——判定是本票的全部难点，把它从执行里拆出来才测得到每一个负例。
 */
class ExecPolicyTest {

    @Test
    @DisplayName("四档预设各自展开，再反解回原档；auto 归 ask")
    void modePresetRoundTrips() {
        assertThat(ExecPolicy.expand(ExecProperties.Mode.DENY).security())
                .isEqualTo(ExecProperties.Security.DENY);
        assertThat(ExecPolicy.expand(ExecProperties.Mode.ALLOWLIST))
                .isEqualTo(new ExecPolicy.Settings(ExecProperties.Security.ALLOWLIST,
                        ExecProperties.Ask.OFF, false, ExecProperties.Host.AUTO, false));
        assertThat(ExecPolicy.expand(ExecProperties.Mode.ASK))
                .isEqualTo(new ExecPolicy.Settings(ExecProperties.Security.ALLOWLIST,
                        ExecProperties.Ask.ON_MISS, false, ExecProperties.Host.AUTO, false));
        assertThat(ExecPolicy.expand(ExecProperties.Mode.AUTO))
                .isEqualTo(new ExecPolicy.Settings(ExecProperties.Security.ALLOWLIST,
                        ExecProperties.Ask.ON_MISS, true, ExecProperties.Host.AUTO, false));
        assertThat(ExecPolicy.expand(ExecProperties.Mode.FULL))
                .isEqualTo(new ExecPolicy.Settings(ExecProperties.Security.FULL,
                        ExecProperties.Ask.OFF, false, ExecProperties.Host.AUTO, false));

        for (ExecProperties.Mode mode : List.of(ExecProperties.Mode.DENY,
                ExecProperties.Mode.ALLOWLIST, ExecProperties.Mode.ASK, ExecProperties.Mode.FULL)) {
            assertThat(ExecPolicy.modeOf(ExecPolicy.expand(mode))).isEqualTo(mode);
        }
        // auto 认不回来：是否接了复核器是另一件事，档位不该假装表达它
        assertThat(ExecPolicy.modeOf(ExecPolicy.expand(ExecProperties.Mode.AUTO)))
                .isEqualTo(ExecProperties.Mode.ASK);
    }

    @Test
    @DisplayName("allowlist + ask=off：未命中不弹，直接拒")
    void allowlistOffMissIsDeniedWithoutPrompt() {
        ExecPolicy.Settings settings = ExecPolicy.expand(ExecProperties.Mode.ALLOWLIST);
        assertThat(ExecPolicy.requiresApproval(settings, true, false, false)).isFalse();
    }

    @Test
    @DisplayName("ask=on-miss：白名单命中直接跑")
    void askOnMissRunsAllowlistedWithoutPrompt() {
        ExecPolicy.Settings settings = ExecPolicy.expand(ExecProperties.Mode.ASK);
        assertThat(ExecPolicy.requiresApproval(settings, true, true, false)).isFalse();
    }

    @Test
    @DisplayName("ask=on-miss：未命中要问")
    void askOnMissPromptsWhenMissed() {
        ExecPolicy.Settings settings = ExecPolicy.expand(ExecProperties.Mode.ASK);
        assertThat(ExecPolicy.requiresApproval(settings, true, false, false)).isTrue();
    }

    @Test
    @DisplayName("ask=always：命中白名单也问")
    void askAlwaysPromptsEvenOnAllowlisted() {
        ExecPolicy.Settings settings = new ExecPolicy.Settings(ExecProperties.Security.ALLOWLIST,
                ExecProperties.Ask.ALWAYS, false, ExecProperties.Host.AUTO, false);
        assertThat(ExecPolicy.requiresApproval(settings, true, true, false)).isTrue();
    }

    @Test
    @DisplayName("security=full + ask=always 仍然逐条问（反解回 ask）")
    void fullWithAskAlwaysStillPrompts() {
        ExecPolicy.Settings settings = new ExecPolicy.Settings(ExecProperties.Security.FULL,
                ExecProperties.Ask.ALWAYS, false, ExecProperties.Host.AUTO, false);
        assertThat(ExecPolicy.requiresApproval(settings, true, true, true)).isTrue();
        assertThat(ExecPolicy.modeOf(settings)).isEqualTo(ExecProperties.Mode.ASK);
    }

    @Test
    @DisplayName("security=full + ask=off 一次都不问")
    void fullWithAskOffNeverPrompts() {
        ExecPolicy.Settings settings = ExecPolicy.expand(ExecProperties.Mode.FULL);
        assertThat(ExecPolicy.requiresApproval(settings, false, false, false)).isFalse();
    }

    private static ExecPolicy.Settings allowlisted(boolean strictInlineEval) {
        return new ExecPolicy.Settings(ExecProperties.Security.ALLOWLIST, ExecProperties.Ask.OFF,
                false, ExecProperties.Host.AUTO, strictInlineEval);
    }

    @Test
    @DisplayName("heredoc 一律问人：白名单里那条 cat 覆盖不到 << 之后的内容")
    void heredocForcesApproval() {
        // allowlist + ask=off 本来是"未命中直接拒、不弹"，heredoc 把它抬成"问人"：
        // 命令本身命中了 cat，但 << 之后是任意内容，白名单一个字都没覆盖到。
        assertThat(ExecPolicy.heredoc("cat <<EOF")).isTrue();
        assertThat(ExecPolicy.heredoc("cat <<-'EOF'")).isTrue();
        assertThat(ExecPolicy.forceAsk(allowlisted(false), "cat <<EOF")).isTrue();

        assertThat(ExecPolicy.heredoc("cat notes.txt")).isFalse();
        assertThat(ExecPolicy.forceAsk(allowlisted(false), "cat notes.txt")).isFalse();
    }

    @Test
    @DisplayName("strictInlineEval 开着：解释器 + 内联旗标一律问人，即使它在白名单里")
    void strictInlineEvalForcesApproval() {
        assertThat(ExecPolicy.inlineEval("python3 -c \'print(1)\'")).isTrue();
        assertThat(ExecPolicy.inlineEval("bash -c \'rm -rf /\'")).isTrue();
        assertThat(ExecPolicy.inlineEval("node --eval 1")).isTrue();
        assertThat(ExecPolicy.inlineEval("find . -exec rm {} ;")).isTrue();
        assertThat(ExecPolicy.inlineEval("/usr/bin/perl -E \'say 1\'"))
                .as("按 basename 认：路径形态与裸名字是同一个可执行文件")
                .isTrue();

        assertThat(ExecPolicy.forceAsk(allowlisted(true), "python3 -c \'print(1)\'")).isTrue();
        assertThat(ExecPolicy.forceAsk(allowlisted(true), "bash -c \'echo hi\'")).isTrue();
    }

    @Test
    @DisplayName("strictInlineEval 关着时解释器跑脚本文件不被误伤")
    void strictInlineEvalLeavesScriptFileAlone() {
        assertThat(ExecPolicy.inlineEval("python3 script.py")).isFalse();
        assertThat(ExecPolicy.inlineEval("bash deploy.sh")).isFalse();
        assertThat(ExecPolicy.inlineEval("sed -n 1,5p notes.txt"))
                .as("-e 才算内联；-n 是安静模式，不是一段程序")
                .isFalse();

        // 关着的时候：命中的是 python3 这条白名单，就该直接跑，不该为"解释器"这个身份多问一次。
        assertThat(ExecPolicy.forceAsk(allowlisted(false), "python3 script.py")).isFalse();
    }

    @Test
    @DisplayName("两条都是叠加在 RUN 上的：已经 DENY 的命令不会被它们改判成 ASK")
    void forceAskOnlyWidensRun() {
        ExecPolicy.Settings deny = ExecPolicy.expand(ExecProperties.Mode.DENY);

        assertThat(ExecPolicy.verdict(deny, "cat <<EOF", true, false)).isEqualTo(ExecPolicy.Verdict.DENY);
        assertThat(ExecPolicy.forceAsk(deny, "cat <<EOF"))
                .as("forceAsk 只回答'要不要问人'，改判留给调用方，且只抬 RUN——否则 deny 会被 heredoc 撬开")
                .isTrue();
    }

    @Test
    @DisplayName("full + on-miss 不问：full 下没有未命中这回事")
    void fullWithOnMissDoesNotPrompt() {
        ExecPolicy.Settings settings = new ExecPolicy.Settings(ExecProperties.Security.FULL,
                ExecProperties.Ask.ON_MISS, false, ExecProperties.Host.AUTO, false);
        assertThat(ExecPolicy.requiresApproval(settings, true, false, false)).isFalse();
    }

    @Test
    @DisplayName("always 优先于持久批准：批过一次之后下一次仍然问")
    void alwaysBeatsDurableApproval() {
        ExecPolicy.Settings settings = new ExecPolicy.Settings(ExecProperties.Security.ALLOWLIST,
                ExecProperties.Ask.ALWAYS, false, ExecProperties.Host.AUTO, false);
        assertThat(ExecPolicy.requiresApproval(settings, true, true, true)).isTrue();
    }

    @Test
    @DisplayName("on-miss 认持久批准：落过 allow-always 就不再问")
    void onMissRespectsDurableApproval() {
        ExecPolicy.Settings settings = ExecPolicy.expand(ExecProperties.Mode.ASK);
        assertThat(ExecPolicy.requiresApproval(settings, true, false, true)).isFalse();
    }

    @Test
    @DisplayName("解析不出 argv 时按未命中等价处理，要问")
    void analysisFailureForcesPrompt() {
        ExecPolicy.Settings settings = ExecPolicy.expand(ExecProperties.Mode.ASK);
        assertThat(ExecPolicy.requiresApproval(settings, false, true, false)).isTrue();
    }

    @Test
    @DisplayName("入口说不清的段算分析失败：文本命中白名单也要问")
    void unnamableSegmentIsAnalysisFailure() {
        assertThat(ExecPolicy.analysisOk("rg -n TODO && ls")).isTrue();
        assertThat(ExecPolicy.analysisOk("./run.sh")).isFalse();
        assertThat(ExecPolicy.analysisOk("FOO=bar rg TODO")).isFalse();

        ExecPolicy.Settings settings = ExecPolicy.expand(ExecProperties.Mode.ASK);
        assertThat(ExecPolicy.verdict(settings, "./run.sh", true, false))
                .as("白名单按整条命令文本匹配，./run.sh 能配进去，但它是什么程序取决于那个文件")
                .isEqualTo(ExecPolicy.Verdict.ASK);
    }

    @Test
    @DisplayName("兜底档被 security 夹住：allowlist 下写 full 也不放行")
    void fallbackIsClampedBySecurity() {
        // 照它的 minSecurity(effectiveSecurity, askFallback)：两个配置项打架时取更严的那个。
        // allowlist 那一档的全部意义是"未命中就是不跑"，一个 askFallback=full 就能把它拆掉的话，
        // "硬边界"这个词就不成立了。
        assertThat(ExecPolicy.fallbackAllows(new ExecPolicy.Settings(ExecProperties.Security.ALLOWLIST,
                ExecProperties.Ask.ALWAYS, false, ExecProperties.Host.AUTO, false,
                ExecProperties.AskFallback.FULL))).isFalse();
        assertThat(ExecPolicy.fallbackAllows(new ExecPolicy.Settings(ExecProperties.Security.FULL,
                ExecProperties.Ask.ALWAYS, false, ExecProperties.Host.AUTO, false,
                ExecProperties.AskFallback.FULL))).isTrue();
        assertThat(ExecPolicy.fallbackAllows(new ExecPolicy.Settings(ExecProperties.Security.DENY,
                ExecProperties.Ask.OFF, false, ExecProperties.Host.AUTO, false,
                ExecProperties.AskFallback.FULL))).isFalse();
        assertThat(ExecPolicy.fallbackAllows(new ExecPolicy.Settings(ExecProperties.Security.FULL,
                ExecProperties.Ask.ALWAYS, false, ExecProperties.Host.AUTO, false,
                ExecProperties.AskFallback.ALLOWLIST)))
                .as("能走到这一步的正是没命中白名单的那些，所以 allowlist 兜底恒等于拒")
                .isFalse();
    }

    @Test
    @DisplayName("显式字段逐字段盖过 mode")
    void settingsOverridesPerField() {
        ExecProperties properties = new ExecProperties(ExecProperties.Mode.FULL,
                null, ExecProperties.Ask.ALWAYS, null, null, null, null, Path.of("."), List.of());
        ExecPolicy.Settings settings = properties.settings();
        assertThat(settings.security()).isEqualTo(ExecProperties.Security.FULL);
        assertThat(settings.ask()).isEqualTo(ExecProperties.Ask.ALWAYS);
        assertThat(settings.autoReview()).isFalse();
    }

    @Test
    @DisplayName("复合命令按分隔符拆段，空段丢掉")
    void segmentsSplitCompoundCommand() {
        assertThat(ExecPolicy.segments("a && b || c; d | e & f\n g"))
                .containsExactly("a", "b", "c", "d", "e", "f", "g");
        assertThat(ExecPolicy.segments("   ")).isEmpty();
        assertThat(ExecPolicy.segments(null)).isEmpty();
    }

    @Test
    @DisplayName("pattern 只认干净的可执行名，路径与赋值不落库")
    void patternOfRejectsPathsAndAssignments() {
        assertThat(ExecPolicy.patternOf("rg -n TODO")).contains("rg");
        assertThat(ExecPolicy.patternOf("  git status")).contains("git");
        // 路径形态：取 basename 会让 /tmp/evil/rg 与 rg 共用一条 pattern，宁可不落
        assertThat(ExecPolicy.patternOf("/usr/bin/rg -n TODO")).isEmpty();
        assertThat(ExecPolicy.patternOf("FOO=bar cmd")).isEmpty();
        assertThat(ExecPolicy.patternOf("$SHELL -c x")).isEmpty();
        assertThat(ExecPolicy.patternOf("\"quoted\"" )).isEmpty();
        assertThat(ExecPolicy.patternOf("")).isEmpty();
    }
}
