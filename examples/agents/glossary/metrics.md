成交额 = `sum(order_item.amount)`，且 `status <> 'CANCELLED'`。已取消的订单不计入。

指定区间 = `order_date between 起始 and 结束`，两端都含。用户给了起止日期就照它算，
不要擅自换算成"上周""最近七天"这种相对说法。

上周 = 半开区间 `[date_trunc('week', current_date) - interval '7 days', date_trunc('week', current_date))`。
（这是口径定义，不等于库里一定有那一段的数据。）

区域代码：`east` = 华东，`south` = 华南，`north` = 华北。
写中文区域名不会报错，只会返回 0 行——这是自纠错唯一发现不了的错误，所以区域一律用代码。
