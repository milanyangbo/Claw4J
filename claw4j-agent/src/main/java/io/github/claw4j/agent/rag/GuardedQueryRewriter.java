package io.github.claw4j.agent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 检索改写的保护层：改写本身交给框架（{@code RewriteQueryTransformer}），这里只做它不做的四件事。
 *
 * <p><b>为什么不自己调模型</b>：Spring AI 2.0.2 自带 {@code RewriteQueryTransformer}，
 * 提示词与目标检索系统都能通过 builder 换掉，输出为空时也已经回落原 query。自己再写一遍
 * "拼 Prompt、取 content" 只是把那部分重新实现一次，还少不了一处要跟着框架升级。
 *
 * <p>它没有的四件事，正好是我们三轮评测踩出来的：
 * <ol>
 *   <li><b>超时</b>：{@code ChatClient.call()} 会一直等。改写挂在检索之前，用户等的是首字，
 *       等不到就该放弃——一次白花的调用，比让用户陪着等便宜。
 *   <li><b>跳过</b>：{@link RewriteGuard#shouldSkip} 为真时一次模型都不调。有些问题改写了必然坏事
 *       （精确值被近似化），知道是这类就别发请求。
 *   <li><b>校验</b>：{@link RewriteGuard#keepsAll} 为假就回落。模型把型号改掉时，检索会跑到
 *       另一款产品的文档上，而且从结果看不出来。
 *   <li><b>清洗</b>：模型经常顺手加一句解释，或照着示例回「改写：xxx」。这些字进了检索就是噪声词。
 * </ol>
 *
 * <p>四者都是"改写失败"的形态，统一行为：<b>拿原问题继续检索</b>。改写是增强，不是前提——
 * 让"改写没成"变成"这一轮检索不到"，是把一个可选优化做成了单点故障。
 */
public class GuardedQueryRewriter implements QueryTransformer {

    private static final Logger log = LoggerFactory.getLogger(GuardedQueryRewriter.class);

    /** 改写结果最多留这么多字符。再长就是模型开始解释，而解释会被当成检索词。 */
    private static final int MAX_QUERY_CHARS = 60;

    private final Supplier<QueryTransformer> delegate;

    private final RewriteGuard guard;

    private final Duration timeout;

    /** 一次改写一个虚拟线程：等待是真等待（HTTP），不需要占用平台线程。 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 第一次用时才构造真正的 transformer——自动配置早于模型那一层，见装配处的说明。 */
    private volatile QueryTransformer resolved;

    /**
     * @param delegate 取真正的改写器；用 {@code Supplier} 是为了把"解析模型"推迟到第一次检索
     * @param guard    保护层，决定跳过与校验
     * @param timeout  超过就按原问题检索
     */
    public GuardedQueryRewriter(Supplier<QueryTransformer> delegate, RewriteGuard guard, Duration timeout) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * 改写一次。任何一条保护触发都返回原 query，不改写时也返回同一个对象（调用方按"没变"处理）。
     *
     * @param query 本次查询
     * @return 改写后的查询，或原查询
     */
    @Override
    public Query transform(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return query;
        }
        String question = query.text().strip();
        if (guard.shouldSkip(question)) {
            log.debug("问题里含数值或比例，跳过改写（避免把精确值改近似）：{}", question);
            return query;
        }
        Future<Query> future = executor.submit(() -> transformer().transform(query));
        try {
            Query transformed = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (transformed == null || transformed.text() == null || transformed.text().isBlank()) {
                return query;
            }
            String cleaned = clean(transformed.text());
            if (cleaned.isEmpty()) {
                return query;
            }
            if (!guard.keepsAll(question, cleaned)) {
                log.debug("改写丢了原问题的标识，按原问题检索：「{}」→「{}」", question, cleaned);
                return query;
            }
            log.debug("检索改写：「{}」→「{}」", question, cleaned);
            return transformed.mutate().text(cleaned).build();
        }
        catch (TimeoutException timeout) {
            // 不能中断一次已经发出去的 HTTP 调用：这里只停止等待，那一轮请求会在后台跑完。
            // 代价是一次白花的调用，收益是用户不必等它。
            future.cancel(true);
            log.warn("检索改写超过 {} 没回来，按原问题检索", this.timeout);
            return query;
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return query;
        }
        catch (ExecutionException failure) {
            log.warn("检索改写失败，按原问题检索：{}", failure.getCause() == null
                    ? failure.getMessage() : failure.getCause().getMessage());
            return query;
        }
    }

    /** 第一次调用时构造真正的 transformer，之后复用。 */
    private QueryTransformer transformer() {
        QueryTransformer current = resolved;
        if (current == null) {
            synchronized (this) {
                if (resolved == null) {
                    resolved = delegate.get();
                }
                current = resolved;
            }
        }
        return current;
    }

    /** 取第一行、剥标签与引号、截断到上限——都是模型输出稳定性问题，框架不管。 */
    private static String clean(String text) {
        String firstLine = text.strip().lines().findFirst().orElse("").strip();
        // 模型偶尔会照着示例的样子回「改写：xxx」，把标签去掉再当查询用。
        if (firstLine.startsWith("改写：")) {
            firstLine = firstLine.substring("改写：".length()).strip();
        }
        if (firstLine.length() > MAX_QUERY_CHARS) {
            firstLine = firstLine.substring(0, MAX_QUERY_CHARS);
        }
        return firstLine.replaceAll("^[\"'“”「」]+|[\"'“”「」]+$", "").strip();
    }
}
