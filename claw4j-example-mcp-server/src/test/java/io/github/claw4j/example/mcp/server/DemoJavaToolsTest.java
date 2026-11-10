package io.github.claw4j.example.mcp.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具本体：不经过协议，直接调。
 *
 * <p>补的是覆盖率那一半：{@code JavaMcpServerProcessTest} 把 server 起在另一个进程里，
 * 那边跑得再对，jacoco 也只看得见自己的 JVM，记不到本进程。于是工具的实际行为在这里有单元级
 * 断言，协议那一半由进程级用例负责。
 */
class DemoJavaToolsTest {

    private static final Instant PINNED = Instant.parse("2026-10-28T00:00:00Z");

    private final DemoJavaTools tools = new DemoJavaTools(Clock.fixed(PINNED, ZoneOffset.UTC));

    @Test
    @DisplayName("echo 原样带回：跨进程用例靠它判断帧里的字符没被改写")
    void echoReturnsTheSameText() {
        assertThat(tools.echo("跨进程往返没丢字")).isEqualTo("跨进程往返没丢字");
    }

    @Test
    @DisplayName("serverTime 来自注入的时钟：钉住了才敢断言‘这确实是那一个时刻’")
    void serverTimeComesFromTheClock() {
        assertThat(tools.serverTime()).isEqualTo(PINNED.toString());
    }

    @Test
    @DisplayName("processId 是当前进程的 pid：进程级用例拿它来证明自己连的是另一个进程")
    void processIdIsTheCurrentProcess() {
        assertThat(tools.processId()).isEqualTo(ProcessHandle.current().pid());
    }
}
