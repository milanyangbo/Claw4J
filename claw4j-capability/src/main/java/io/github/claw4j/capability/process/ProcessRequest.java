package io.github.claw4j.capability.process;

import java.util.List;
import java.util.Objects;

/**
 * 一次进程执行的请求：一个 argv 数组，不是一个字符串（ADR-0002，票 08）。
 *
 * <p>差别在于谁来解释它。一条命令行字符串要交给解释器才能执行，而解释器会把参数里的
 * {@code ;}、{@code $(...)}、反引号当成自己的语法；数组形态没有解释器这一步，元素是什么，
 * 进程收到的就是什么，"参数里带元字符"从"要靠转义挡住"变成"没有东西会解释它"。
 *
 * <p>没有默认超时是有意的：接缝上给一个默认值等于替调用方申请了一个越界的预算——沙箱上限是
 * 30 秒，而这件事在调用处看不出来。超时必须由调用方明说。
 *
 * @param argv 程序与其参数，第一项是程序；直接执行，不经任何解释器
 * @param timeoutMillis 墙上时钟上限，正数
 */
public record ProcessRequest(List<String> argv, long timeoutMillis) {

    public ProcessRequest {
        Objects.requireNonNull(argv, "argv");
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("argv 不能为空：没有程序就没有可执行的请求");
        }
        if (argv.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("argv 里不能有 null：数组不经解释器，null 没有可表示的形式");
        }
        if (argv.getFirst().isBlank()) {
            throw new IllegalArgumentException("argv 的第一项（程序）不能是空白");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("超时必须为正数，拿到的是 " + timeoutMillis + "ms");
        }
    }

    /** argv 的第一项：要执行的那个程序。 */
    public String program() {
        return argv.getFirst();
    }
}
