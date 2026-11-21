package io.github.claw4j.agent.webtools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * 出网边界：{@code WebFetch} 只允许抓公网上的 http / https。
 *
 * <p>对齐 OpenClaw 威胁模型 T-EXFIL-001 的缓解（*SSRF blocking for internal/private networks*）。
 * 它不是"注入的入口"，是<b>注入的出口</b>：注入成功之后，攻击者让 agent 把上下文拼进 URL
 * 抓一次，数据就出去了。
 *
 * <p>为什么需要它：库自带的"域名安全检查"（{@code DefaultDomainCanFetchChecker}）只是向
 * {@code claude.ai/api/web/domain_info} 问一句黑名单，<b>不解析 DNS、不判 IP 段</b>，
 * 而且默认 {@code failOpenOnSafetyCheckError = true}——那条 API 不可达时一律放行。
 * 实测（2026-11-17）它能抓通 {@code http://127.0.0.1:8123/}。
 *
 * <p><b>只校验首跳。</b>库内部 {@code HttpClient} 用的是 {@code Redirect.ALWAYS}，我们拿不到逐跳钩子；
 * 「公网 302 跳到内网」这一条仍然通（见票 03 的缺口）。挡住的是绝大多数真实试探：直接写
 * {@code 127.0.0.1} / {@code 10.x} / {@code 169.254.169.254} 的那种。
 */
public final class WebFetchGuard implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(WebFetchGuard.class);

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** 拒绝时回给模型的话。要确定，不要像异常。 */
    static final String REFUSED = "这个地址不在允许出网的范围内（只允许公网 http/https）：";

    private final ToolCallback delegate;

    private WebFetchGuard(ToolCallback delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * 包一层校验。
     *
     * @param delegate 真正抓网页的工具
     * @return 带出网边界的工具
     */
    public static ToolCallback guarding(ToolCallback delegate) {
        return new WebFetchGuard(delegate);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        String refusal = refusalFor(toolInput);
        return refusal != null ? refusal : delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String refusal = refusalFor(toolInput);
        return refusal != null ? refusal : delegate.call(toolInput, toolContext);
    }

    /**
     * 校验这次请求的 URL。
     *
     * @param toolInput 工具入参 JSON
     * @return 拒绝理由；允许时为 {@code null}
     */
    static String refusalFor(String toolInput) {
        String url;
        try {
            JsonNode node = JSON.readTree(toolInput == null ? "{}" : toolInput);
            url = node.path("url").asText("");
        }
        catch (Exception notJson) {
            // 解析不了就交给下游：下游会自己报参数错误，那比我猜一个理由更准确。
            return null;
        }
        if (url.isBlank()) {
            return null;
        }
        return refusalForUrl(url);
    }

    /**
     * 校验一个 URL：协议、以及主机解析出来的<b>每一个</b>地址。
     *
     * <p>按解析后的 IP 判，不按域名文本：{@code http://127.0.0.1.nip.io} 解析出来就是环回。
     *
     * @param url 目标地址
     * @return 拒绝理由；允许时为 {@code null}
     */
    static String refusalForUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        }
        catch (IllegalArgumentException bad) {
            return REFUSED + "URL 不合法";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return REFUSED + "只支持 http / https";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return REFUSED + "没有主机名";
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (forbidden(address)) {
                    log.debug("拒绝出网：url={} host={} 解析到 {}", url, host, address.getHostAddress());
                    return REFUSED + host;
                }
            }
        }
        catch (UnknownHostException unknown) {
            return REFUSED + host + "（域名解析不出来）";
        }
        return null;
    }

    /**
     * 这个地址是不是"不该从 agent 里访问"的。
     *
     * @param address 解析后的地址
     * @return 该拒绝就是 true
     */
    static boolean forbidden(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            // 0.0.0.0/8：部分实现把它当"本机"
            if (first == 0) {
                return true;
            }
            // 100.64/10：运营商级 NAT，云上常见
            return first == 100 && second >= 64 && second <= 127;
        }
        int first = bytes[0] & 0xff;
        // fc00::/7 unique local：isSiteLocalAddress 对 IPv6 不覆盖
        if ((first & 0xfe) == 0xfc) {
            return true;
        }
        // ::ffff:a.b.c.d：映射地址按里面那个 IPv4 判
        boolean mapped = true;
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                mapped = false;
                break;
            }
        }
        if (mapped && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff) {
            try {
                return forbidden(InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16)));
            }
            catch (UnknownHostException impossible) {
                return true;
            }
        }
        return false;
    }
}
