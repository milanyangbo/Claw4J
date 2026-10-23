-- 销售域的骨架：区域 → 客户 → 订单 → 订单项。
--
-- 全部落在 business schema 下，跟会话表、向量表分开（ADR-0005：一个 Postgres 承担三件事）。
-- 后面加会话或向量时不会跟业务表抢名字。
create schema if not exists business;

create table business.region (
    code varchar(32) primary key,
    name varchar(64) not null
);

create table business.customer (
    id          bigint generated always as identity primary key,
    code        varchar(64)  not null unique,
    name        varchar(128) not null,
    region_code varchar(32)  not null references business.region(code)
);

create table business.sales_order (
    id          bigint generated always as identity primary key,
    order_no    varchar(64) not null unique,
    customer_id bigint      not null references business.customer(id),
    -- 区域冗余在订单上，客户表里也有一份。明知冗余还留着，是因为 Text-to-SQL 要生成
    -- 人类看得懂的 SQL：少一层 join 就少一个模型写错的地方。一致性由外键与写入路径保证。
    region_code varchar(32) not null references business.region(code),
    order_date  date        not null,
    status      varchar(32) not null check (status in ('PAID', 'SHIPPED', 'CANCELLED'))
);

-- 成交额查询永远是"时间窗 + 区域"，索引按这个顺序建。
create index sales_order_date_region_idx on business.sales_order (order_date, region_code);

create table business.order_item (
    id           bigint generated always as identity primary key,
    order_id     bigint         not null references business.sales_order(id),
    product_name varchar(128)   not null,
    quantity     integer        not null check (quantity > 0),
    unit_price   numeric(12, 2) not null,
    -- 生成列：金额恒等于数量 × 单价。改了数量却忘了同步金额这种脏数据，
    -- 在数据库这一层就造不出来。
    amount       numeric(14, 2) generated always as (quantity * unit_price) stored
);

create index order_item_order_idx on business.order_item (order_id);
