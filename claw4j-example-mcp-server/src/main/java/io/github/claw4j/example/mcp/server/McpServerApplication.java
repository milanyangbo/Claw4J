package io.github.claw4j.example.mcp.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 一个独立运行的 Java MCP server：{@code /mcp} 上说 Streamable HTTP，工具来自 {@link OperationalTools}。
 *
 * <p>这是一个普通的 Spring Boot 应用，MCP 那一层由 {@code spring-ai-starter-mcp-server-webmvc}
 * 装配：端口、端点路径、协议版本都在 {@code application.yml} 里，工具是带 {@code @McpTool} 的
 * 普通方法，由注解扫描器收进清单——没有一行手写的传输代码。
 *
 * <p>为什么不是 stdio：stdio 那一路要求宿主与 server 在同一台机器上并且能被 fork；
 * 公网上的第三方服务、容器里的 server、要独立扩容的 server，都只能走 HTTP。2025 版协议用
 * Streamable HTTP 取代了 SSE，所以这里是 Streamable，端点默认 {@code /mcp}。
 *
 * <p>宿主侧照抄这三行（{@code application-local.yml}）：
 * <pre>{@code
 * java-http:
 *   transport: http
 *   url: http://127.0.0.1:8082
 *   permission: { action: read }
 * }</pre>
 *
 * <p>启动顺序是<b>先起它、再起宿主</b>。反过来也能跑：宿主先起时这条连接记 DOWN，
 * 之后按 15 秒起、每次翻倍、封顶 10 分钟退避重连，起来之后不必重启宿主。
 * 这两种顺序在日志里都看得见，是测试手册里那一节的内容。
 */
@SpringBootApplication
public class McpServerApplication {

    /**
     * 起一个 HTTP MCP server，端口默认 8082，可用 {@code CLAW4J_EXAMPLE_MCP_PORT} 覆盖。
     *
     * @param args Spring Boot 的命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
