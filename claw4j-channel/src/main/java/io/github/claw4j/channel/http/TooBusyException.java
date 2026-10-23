package io.github.claw4j.channel.http;

/**
 * 流式线程池已满，提交被同步拒绝。
 *
 * <p>这属于服务过载（503）而不是请求本身的错：调用方重试有意义，
 * 但立刻重试只会让队列更满，所以响应体里明说"稍后再试"。
 */
class TooBusyException extends RuntimeException {

    TooBusyException() {
        super("服务繁忙，请稍后再试");
    }
}
