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
    region_code varchar(32)  not null references business.region(code),
    -- 为订单的复合外键准备：它要引用的是"客户 id + 客户所在区域"这个组合，
    -- 光有主键不够，外键目标必须是唯一的。
    unique (id, region_code)
);

create table business.sales_order (
    id          bigint generated always as identity primary key,
    order_no    varchar(64) not null unique,
    customer_id bigint      not null references business.customer(id),
    -- 区域冗余在订单上，客户表里也有一份。明知冗余还留着，是因为 Text-to-SQL 要生成
    -- 人类看得懂的 SQL：少一层 join 就少一个模型写错的地方。
    -- 冗余的代价（两个来源可能对不上）由下面这条复合外键兜住：
    -- 订单只能引用"某客户的 id + 该客户自己的区域"，写不出区域与客户矛盾的订单。
    -- 否则"按区域汇总"和"按客户汇总"会给出两个不一样的总数，而两个都算不出来错在哪。
    region_code varchar(32) not null,
    order_date  date        not null,
    status      varchar(32) not null check (status in ('PAID', 'SHIPPED', 'CANCELLED')),
    foreign key (customer_id, region_code) references business.customer (id, region_code)
);

-- 成交额查询永远是"等值区域 + 范围时间"，等值列放前面，范围列放后面。
create index sales_order_region_date_idx on business.sales_order (region_code, order_date);

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
