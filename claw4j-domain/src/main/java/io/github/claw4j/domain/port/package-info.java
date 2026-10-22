/**
 * 端口（Port）：模块之间互相调用的契约，只放接口与消息类型，不放实现。
 *
 * <p>这里的类型必须能独立编译——除了本模块，不允许出现任何框架或供应商的 import。
 * 这条由 {@code ArchitectureTest} 用 ArchUnit 机器校验，不靠自觉。
 *
 * <p>为什么端口放在 domain 而不是调用方模块：如果 AgentPort 定义在 agent 模块，
 * channel 就必须依赖 agent，于是 agent 的任何改动都会波及所有通道。
 * 放在 domain，两边都只朝 domain 依赖，装配权交给 app。
 */
package io.github.claw4j.domain.port;
