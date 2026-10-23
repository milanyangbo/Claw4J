package io.github.claw4j.tools.sales;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 业务库的数据源接缝：把"某个区域在某个时间窗内成交了多少"收敛成一个参数化查询。
 *
 * <p>它是 M1 三条接缝里最底下那条（另两条是 {@code AgentPort} 与 {@code @Tool} 方法）。
 * 管的是"给定参数能不能算出正确的数"，不管模型怎么决定调它、也不管回答怎么组织。
 *
 * <p><b>成交额口径</b>：只统计未取消的订单（{@code PAID} / {@code SHIPPED}），
 * 已取消的不算。这个口径写死在 SQL 里而不是留给调用方，是因为它一旦不一致，
 * 表现是"两个数都对但不一样"——最难查的一类问题。
 *
 * <p>为什么是参数化 SQL 而不是 ORM：05 号票的模型要生成 SQL，它面对的必须是
 * 跟人写的 SQL 长得一样的东西。中间隔一层实体映射，模型生成的 SQL 与这里执行的
 * SQL 就不再是同一门语言了。
 */
public class SalesDataRepository {

    private final JdbcClient jdbc;

    public SalesDataRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * 指定区域在 {@code [fromInclusive, toExclusive)} 内的成交额。
     * 没有命中任何订单时返回 {@link BigDecimal#ZERO}，不返回 {@code null}。
     *
     * @throws IllegalArgumentException 时间窗是空的或反的。这种情况静默返回 0 的话，
     *                                  排查时看到的是"没数据"而不是"条件写错了"，方向完全不同。
     */
    public BigDecimal sumAmount(String regionCode, LocalDate fromInclusive, LocalDate toExclusive) {
        Objects.requireNonNull(regionCode, "regionCode");
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException(
                    "时间窗必须非空：fromInclusive=" + fromInclusive + " 不早于 toExclusive=" + toExclusive);
        }

        return jdbc.sql("""
                        select coalesce(sum(oi.amount), 0)
                        from business.sales_order o
                                 join business.order_item oi on oi.order_id = o.id
                        where o.region_code = :regionCode
                          and o.status <> 'CANCELLED'
                          and o.order_date >= :from
                          and o.order_date < :to
                        """)
                .param("regionCode", regionCode)
                .param("from", fromInclusive)
                .param("to", toExclusive)
                .query(BigDecimal.class)
                .single();
    }
}
