package io.github.claw4j.persona;

/**
 * 系统提示词的两半：稳定的那一半与每轮都会变的那一半。
 *
 * <p>为什么要分开：供应商的前缀缓存按"从头开始逐字节相同"命中。把日期拼在整段里，跨天之后
 * 前缀全变，缓存一次也用不上；分开之后，稳定段的字节可以长期不变，变的只有后面那一小段
 * （OpenClaw 的 "缓存边界" 是同一件事，只是它把边界做进了传输层，我们做在拼装这一层）。
 *
 * @param stable   人设与口径：同一份人设跨天、跨请求都不变
 * @param variable 现在的时间：每轮按调用方传入的时刻重算
 */
public record PromptParts(String stable, String variable) {
}
