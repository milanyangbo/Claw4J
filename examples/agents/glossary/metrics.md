成交额 = `sum(order_item.amount)`，且 `status <> 'CANCELLED'`。已取消的订单不计入。

上周 = 半开区间 `[date_trunc('week', current_date) - interval '7 days', date_trunc('week', current_date))`。

区域代码：`east` = 华东，`south` = 华南，`north` = 华北。
写中文区域名不会报错，只会返回 0 行——这是自纠错唯一发现不了的错误，所以区域一律用代码。
