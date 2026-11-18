package io.github.claw4j.capability.exec;

import io.github.claw4j.domain.principal.Elevation;
import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.common.exec.ExecBackend;
import org.springaicommunity.agent.common.exec.ExecHandle;
import org.springaicommunity.agent.common.exec.ExecResult;
import org.springaicommunity.agent.common.exec.ExecSpec;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 提权（{@code /elevated}）对执行的影响：跑在哪儿、还要不要问人。
 *
 * <p>走的是生产那条路：把档位放进 {@link ExecCallContext}，由后端自己读——直接给后端塞一个
 * 参数的话，验的就不是"工具调用时那段上下文有没有接上"。
 *
 * <p>断言的是行为而不是调用顺序：宿主与容器各是一个会计数的假后端，谁被调到就是谁跑的。
 */
class ExecElevationTest {

    @TempDir
    Path workspace;

    @Test
    @DisplayName("不提权：命令交给容器，宿主一次都不跑")
    void offRunsInContainer() {
        Recorder recorder = new Recorder();

        recorder.run(Elevation.OFF, ExecProperties.Mode.FULL);

        assertThat(recorder.containerCalls.get()).isEqualTo(1);
        assertThat(recorder.hostCalls.get()).isZero();
    }

    @Test
    @DisplayName("/elevated on：跑到沙箱外，审批照旧问一次")
    void onRunsOnHostAndKeepsApprovals() {
        Recorder recorder = new Recorder();

        recorder.run(Elevation.ON, ExecProperties.Mode.ASK);

        assertThat(recorder.approvalsAsked.get()).as("on 不跳审批").isEqualTo(1);
        assertThat(recorder.hostCalls.get()).as("提权 = 跑到沙箱外").isEqualTo(1);
        assertThat(recorder.containerCalls.get()).isZero();
    }

    @Test
    @DisplayName("/elevated full：跳过审批，一条本该被问的命令直接跑")
    void fullSkipsApprovals() {
        Recorder recorder = new Recorder();

        recorder.run(Elevation.FULL, ExecProperties.Mode.ASK);

        assertThat(recorder.approvalsAsked.get()).as("full 就是「这一段不问了」").isZero();
        assertThat(recorder.hostCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("security=deny 压过提权：full 也不能把「一条都不放」改成放行")
    void fullDoesNotOverrideDeny() {
        Recorder recorder = new Recorder();

        assertThatThrownBy(() -> recorder.run(Elevation.FULL, ExecProperties.Mode.DENY))
                .isInstanceOf(ExecViolationException.class)
                .hasMessageContaining("security=deny");
        assertThat(recorder.hostCalls.get()).isZero();
    }

    /** 两个假后端 + 一个会计数的审批端口；审批一律放行，好让"跑没跑"这件事可断言。 */
    private final class Recorder {

        final AtomicInteger hostCalls = new AtomicInteger();

        final AtomicInteger containerCalls = new AtomicInteger();

        final AtomicInteger approvalsAsked = new AtomicInteger();

        void run(Elevation elevation, ExecProperties.Mode mode) {
            SandboxedExecBackend backend = new SandboxedExecBackend(
                    ExecProperties.of(mode, workspace, List.of()),
                    counting(hostCalls), Set.of("PATH"), "tester", approving(), ExecReviewer.fallback(),
                    counting(containerCalls));
            ExecCallContext context = new ExecCallContext(new Principal("tester"), "session-1",
                    "http", "chat-1", "run-1", elevation);
            Supplier<ExecResult> call = () -> backend.run(ExecSpec.of("echo hi"));
            ExecCallContext.with(context, call).stdout();
        }

        private ExecBackend counting(AtomicInteger counter) {
            return new ExecBackend() {

                @Override
                public ExecResult run(ExecSpec spec) {
                    counter.incrementAndGet();
                    return ExecResult.completed(0, "", "");
                }

                @Override
                public ExecHandle start(ExecSpec spec) {
                    throw new UnsupportedOperationException("这条用例只走同步那条");
                }
            };
        }

        private ExecApprovalPort approving() {
            return new ExecApprovalPort() {

                @Override
                public Decision decide(Request request) {
                    approvalsAsked.incrementAndGet();
                    return Decision.allowOnce(request.plan());
                }

                @Override
                public boolean alreadyApproved(List<String> segments) {
                    return false;
                }

                @Override
                public void remember(Request request) {
                    // 不落任何东西：这条用例只数"问没问"。
                }
            };
        }
    }
}
