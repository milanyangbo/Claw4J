/**
 * HTTP+SSE 通道。M0 的唯一通道，也是后面换飞书长连接时形状上的参照物。
 *
 * <p>包内分工：{@code HttpChatController} 只管传输，{@code HttpPrincipalResolver} 只管认人，
 * {@code HttpChatChannel} 管"入站怎么交给 Agent"。三者都只朝 domain 与 channel.spi 依赖。
 */
package io.github.claw4j.channel.http;
