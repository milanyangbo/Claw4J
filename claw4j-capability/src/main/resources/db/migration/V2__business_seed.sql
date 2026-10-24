-- 种子数据。目标是让"上周华东区成交额"这个问句在任何一天跑都有唯一确定的答案，
-- 而且这个答案要能被 05 号票的端到端断言直接引用。
--
-- 日期一律相对"本周一"生成（day_offset 是相对本周一的第几天），不写绝对日期：
-- 写死日期的话，过几周"上周"就漂到没有数据的区间，测试开始莫名其妙地返回 0。
-- 代价是读这份文件时得做一点心算，好处是这份种子永远不会过期。
--
-- 金额刻意互不相同，且上周 / 本周 / 三周前三个窗口的数字不一样：
-- 时间窗写错（或压根没写）时，断言会红在具体的数字上，而不是红在"看起来也对"上。

insert into business.region (code, name)
values ('east', '华东'), ('south', '华南'), ('north', '华北');

insert into business.customer (code, name, region_code)
values ('C-EAST-01', '华东客户一', 'east'),
       ('C-EAST-02', '华东客户二', 'east'),
       ('C-SOUTH-01', '华南客户一', 'south'),
       ('C-NORTH-01', '华北客户一', 'north');

insert into business.sales_order (order_no, customer_id, region_code, order_date, status)
select seed.order_no,
       c.id,
       seed.region_code,
       (date_trunc('week', current_date) + (seed.day_offset * interval '1 day'))::date,
       seed.status
from (values
          ('SO-LAST-01', 'C-EAST-01', 'east', -6, 'PAID'),       -- 上周二
          ('SO-LAST-02', 'C-EAST-01', 'east', -4, 'SHIPPED'),    -- 上周四
          ('SO-LAST-03', 'C-EAST-02', 'east', -5, 'CANCELLED'),  -- 上周三，不进成交额
          ('SO-LAST-04', 'C-SOUTH-01', 'south', -3, 'PAID'),     -- 上周五
          ('SO-LAST-05', 'C-NORTH-01', 'north', -7, 'PAID'),     -- 上周一
          ('SO-THIS-01', 'C-EAST-02', 'east', 0, 'PAID'),        -- 本周一
          ('SO-OLD-01', 'C-EAST-01', 'east', -21, 'PAID')        -- 三周前
     ) as seed(order_no, customer_code, region_code, day_offset, status)
         join business.customer c on c.code = seed.customer_code;

insert into business.order_item (order_id, product_name, quantity, unit_price)
select o.id, seed.product_name, seed.quantity, seed.unit_price
from (values
          ('SO-LAST-01', '标准版 License', 3, 12000.00),   -- 36000
          ('SO-LAST-01', '实施服务', 2, 9000.00),          -- 18000  → 上周华东 54000
          ('SO-LAST-02', '旗舰版 License', 5, 14880.00),   -- 74400  → 上周华东合计 128400
          ('SO-LAST-03', '旗舰版 License', 1, 50000.00),   -- 50000  → 已取消，不计入
          ('SO-LAST-04', '标准版 License', 4, 6500.00),    -- 26000  → 上周华南 26000
          ('SO-LAST-05', '实施服务', 1, 31500.00),         -- 31500  → 上周华北 31500
          ('SO-THIS-01', '旗舰版 License', 1, 99999.00),   -- 99999  → 本周华东
          ('SO-OLD-01', '标准版 License', 1, 77777.00)     -- 77777  → 三周前华东
     ) as seed(order_no, product_name, quantity, unit_price)
         join business.sales_order o on o.order_no = seed.order_no;
