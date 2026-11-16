package io.github.claw4j.agent.webtools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出网边界：哪些地址不该从 agent 里访问。
 *
 * <p>判据按<b>解析后的 IP</b>，不按域名文本——{@code 127.0.0.1.nip.io} 这种域名解析出来就是环回。
 * 所以大部分用例直接喂 {@link InetAddress}，不依赖 DNS。
 */
class WebFetchGuardTest {

    @Test
    @DisplayName("环回、私网、链路本地、云元数据、CGNAT 全拒")
    void privateAddressesAreForbidden() throws UnknownHostException {
        String[] refused = {
                "127.0.0.1", "127.255.255.254", "10.1.2.3", "172.16.0.1", "172.31.255.255",
                "192.168.1.1", "169.254.169.254", "100.64.0.1", "100.127.255.255",
                "0.0.0.0", "::1", "fe80::1", "fc00::1", "fd12::1",
        };
        for (String literal : refused) {
            assertTrue(WebFetchGuard.forbidden(InetAddress.getByName(literal)), literal);
        }
    }

    @Test
    @DisplayName("正常公网地址放行：这条防的是'判太宽'，全挡的边界最后只会被人删掉")
    void publicAddressesPass() throws UnknownHostException {
        String[] allowed = {"93.184.216.34", "1.1.1.1", "8.8.8.8", "2606:4700:4700::1111"};
        for (String literal : allowed) {
            assertFalse(WebFetchGuard.forbidden(InetAddress.getByName(literal)), literal);
        }
    }

    @Test
    @DisplayName("IPv4-mapped 的 IPv6 按里面那个 IPv4 判：::ffff:127.0.0.1 也是环回")
    void ipv4MappedAddressesAreUnwrapped() throws UnknownHostException {
        byte[] mapped = new byte[16];
        for (int i = 10; i < 12; i++) {
            mapped[i] = (byte) 0xff;
        }
        mapped[12] = 127;
        mapped[15] = 1;

        assertTrue(WebFetchGuard.forbidden(InetAddress.getByAddress(mapped)));
    }

    @Test
    @DisplayName("域名解析到环回也拒：localhost 不是'一个小域名'")
    void hostnameResolvingToLoopbackIsRefused() {
        assertNotNull(WebFetchGuard.refusalForUrl("http://localhost:8080/x"));
    }

    @Test
    @DisplayName("非 http/https 协议拒：file: 与 gopher: 不是'抓网页'")
    void nonHttpSchemesAreRefused() {
        assertNotNull(WebFetchGuard.refusalForUrl("file:///etc/passwd"));
        assertNotNull(WebFetchGuard.refusalForUrl("gopher://example.com/1"));
        assertNotNull(WebFetchGuard.refusalForUrl("ftp://example.com/a"));
    }

    @Test
    @DisplayName("公网 IP 字面量放行（不依赖 DNS，离线也测得出）")
    void publicLiteralUrlPasses() {
        assertNull(WebFetchGuard.refusalForUrl("https://1.1.1.1/"));
        assertNull(WebFetchGuard.refusalForUrl("http://93.184.216.34/index.html"));
    }

    @Test
    @DisplayName("入参里没有 url 或不是 JSON：交给下游报参数错误，不自己编理由")
    void malformedInputFallsThrough() {
        assertNull(WebFetchGuard.refusalFor("{}"));
        assertNull(WebFetchGuard.refusalFor("not json"));
        assertNull(WebFetchGuard.refusalFor(null));
    }

    @Test
    @DisplayName("入参里的 url 被判：这是模型真正会走的路径")
    void toolInputUrlIsChecked() {
        assertNotNull(WebFetchGuard.refusalFor("{\"url\":\"http://127.0.0.1:8123/\",\"prompt\":\"看看\"}"));
        assertNull(WebFetchGuard.refusalFor("{\"url\":\"https://1.1.1.1/\",\"prompt\":\"看看\"}"));
    }
}
