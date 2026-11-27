-- 业务种子数据。日期一律写死成绝对日期，**不跟"今天"或"建库那天"走**。
--
-- 早先这版用的是「迁移那一刻的本周一 + 偏移」，Flyway 只跑一次，于是那组日期只在建库那一周成立：
-- 过一周「上周」窗口漂到没有数据的区间，判据里的 128400 会变成"查不到"，看着像模型算错。
-- 改成绝对日期之后，判据的数字永远对得上；代价是"上周 / 本周"这类相对问法查不到数——
-- 那是数据的事，不是工具坏了（README-capability-tests.md 的 D3 写了这一条）。
--
-- 文件自己先清空再插入，所以它既能被 Flyway 跑，也能当"重置演示数据"的命令单独执行（幂等）：
--   psql -d <库> -f <本文件>
--
-- 对照表（原种子的 day_offset → 现在的日期）：
--   -21 SO-OLD-01  三周前周一  2026-11-02  77777
--    -7 SO-LAST-05 上周一      2026-11-16  31500（华北）
--    -6 SO-LAST-01 上周二      2026-11-17  36000 + 18000（华东）
--    -5 SO-LAST-03 上周三      2026-11-18  50000（CANCELLED，不计入）
--    -4 SO-LAST-02 上周四      2026-11-19  74400（华东）
--    -3 SO-LAST-04 上周五      2026-11-20  26000（华南）
--     0 SO-THIS-01 本周一      2026-11-23  99999（华东）
-- 于是固定区间 2026-11-16 ~ 2026-11-22：华东 128400、华北 31500、华南 26000、合计 185900；
-- 含 CANCELLED 则是华东 178400。单日 2026-11-19 华东 74400。

delete from business.order_item;
delete from business.sales_order;
delete from business.customer;
delete from business.region;

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
       seed.order_date::date,
       seed.status
from (values
          ('SO-LAST-05', 'C-NORTH-01', 'north', '2026-11-16', 'PAID'),
          ('SO-LAST-01', 'C-EAST-01', 'east', '2026-11-17', 'PAID'),
          ('SO-LAST-03', 'C-EAST-02', 'east', '2026-11-18', 'CANCELLED'),
          ('SO-LAST-02', 'C-EAST-01', 'east', '2026-11-19', 'SHIPPED'),
          ('SO-LAST-04', 'C-SOUTH-01', 'south', '2026-11-20', 'PAID'),
          ('SO-THIS-01', 'C-EAST-02', 'east', '2026-11-23', 'PAID'),
          ('SO-OLD-01', 'C-EAST-01', 'east', '2026-11-02', 'PAID')
     ) as seed(order_no, customer_code, region_code, order_date, status)
         join business.customer c on c.code = seed.customer_code;

insert into business.order_item (order_id, product_name, quantity, unit_price)
select o.id, seed.product_name, seed.quantity, seed.unit_price
from (values
          ('SO-LAST-01', '标准版 License', 3, 12000.00),
          ('SO-LAST-01', '实施服务', 2, 9000.00),
          ('SO-LAST-02', '旗舰版 License', 5, 14880.00),
          ('SO-LAST-03', '旗舰版 License', 1, 50000.00),
          ('SO-LAST-04', '标准版 License', 4, 6500.00),
          ('SO-LAST-05', '实施服务', 1, 31500.00),
          ('SO-THIS-01', '旗舰版 License', 1, 99999.00),
          ('SO-OLD-01', '标准版 License', 1, 77777.00)
     ) as seed(order_no, product_name, quantity, unit_price)
         join business.sales_order o on o.order_no = seed.order_no;
