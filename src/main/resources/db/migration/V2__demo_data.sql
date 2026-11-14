-- Deterministic demo data for a small online coffee-gear shop. Dates are relative to the migration time
-- so "last 30 days" questions always have answers.
SELECT setseed(0.42);

INSERT INTO products (sku, name, category, price_cents, stock, reorder_level) VALUES
 ('GR-100', 'Burr Grinder Pro 64mm',            'Grinders',    24900,  14, 5),
 ('GR-110', 'Hand Grinder Titanium',            'Grinders',    15900,   3, 5),
 ('GR-120', 'Entry Burr Grinder',               'Grinders',     8900,  40, 8),
 ('ES-200', 'Dual Boiler Espresso Machine',     'Espresso',   189900,   4, 2),
 ('ES-210', 'Single Boiler Espresso Machine',   'Espresso',    69900,   9, 3),
 ('ES-220', 'Lever Espresso Maker',             'Espresso',    32900,   0, 2),
 ('BR-300', 'Pour-Over Dripper Ceramic',        'Brewing',      2900,  85, 20),
 ('BR-310', 'Glass Carafe 600ml',               'Brewing',      3400,  60, 15),
 ('BR-320', 'French Press 1L',                  'Brewing',      3900,  33, 10),
 ('BR-330', 'Cold Brew Tower',                  'Brewing',     12900,   6, 4),
 ('BR-340', 'AeroPress Style Brewer',           'Brewing',      3990,  52, 15),
 ('KT-400', 'Gooseneck Kettle Electric',        'Kettles',      8900,  21, 8),
 ('KT-410', 'Stovetop Gooseneck Kettle',        'Kettles',      4500,  12, 8),
 ('SC-500', 'Coffee Scale with Timer',          'Scales',       4900,  47, 12),
 ('SC-510', 'Precision Espresso Scale',         'Scales',       8900,   7, 6),
 ('AC-600', 'Tamper 58mm Stainless',            'Accessories',  3900,  70, 15),
 ('AC-610', 'Milk Pitcher 600ml',               'Accessories',  2400,  95, 20),
 ('AC-620', 'Knock Box',                        'Accessories',  2900,  38, 10),
 ('AC-630', 'Paper Filters 100pk',              'Accessories',   800, 410, 100),
 ('AC-640', 'Cleaning Tablets 50pk',            'Accessories',  1500, 140, 40),
 ('AC-650', 'Bottomless Portafilter 58mm',      'Accessories',  6900,   2, 5),
 ('BN-700', 'Ethiopia Yirgacheffe 250g',        'Beans',        1800, 120, 40),
 ('BN-710', 'Colombia Huila 250g',              'Beans',        1600,  95, 40),
 ('BN-720', 'Kenya AA 250g',                    'Beans',        2100,  18, 40),
 ('BN-730', 'House Espresso Blend 1kg',         'Beans',        3900,  64, 25),
 ('BN-740', 'Decaf Swiss Water 250g',           'Beans',        1700,  30, 20),
 ('MG-800', 'Double Wall Glass Cups (2)',       'Cups',         2600,  44, 12),
 ('MG-810', 'Ceramic Cappuccino Cup Set',       'Cups',         3600,  27, 10),
 ('MG-820', 'Travel Tumbler 350ml',             'Cups',         2900,  58, 15),
 ('MG-830', 'Espresso Cups (4)',                'Cups',         3200,   9, 10);

WITH first_names AS (
    SELECT ARRAY['Olivia','Liam','Emma','Noah','Ava','Lucas','Mia','Ethan','Sofia','Mateo','Chloe','Leo',
                 'Hannah','Oscar','Grace','Felix','Isla','Hugo','Nora','Arjun','Yuki','Lena','Diego','Amara'] AS a
), last_names AS (
    SELECT ARRAY['Smith','Garcia','Müller','Rossi','Kowalski','Novak','Silva','Jensen','Dubois','Tanaka',
                 'Patel','Okafor','Larsen','Moreau','Fischer','Costa','Nguyen','Brennan','Haddad','Weber'] AS a
), countries AS (
    SELECT ARRAY['US','US','US','GB','DE','FR','CA','AU','NL','SE','ES','IT'] AS a
)
INSERT INTO customers (name, email, country, created_at)
SELECT f.a[1 + (g * 7) % 24] || ' ' || l.a[1 + (g * 11) % 20],
       lower(f.a[1 + (g * 7) % 24]) || '.' || lower(replace(l.a[1 + (g * 11) % 20], 'ü', 'u')) || g || '@example.com',
       c.a[1 + (g * 5) % 12],
       now() - make_interval(days => 120 + g * 3)
FROM generate_series(1, 60) g, first_names f, last_names l, countries c;

ALTER SEQUENCE orders_id_seq RESTART WITH 10001;

-- 600 orders over the last 90 days, 1-3 lines each.
INSERT INTO orders (customer_id, status, total_cents, created_at)
SELECT 1 + floor(random() * 60)::int,
       'PAID',
       0,
       date_trunc('minute', now() - make_interval(mins => floor(random() * 90 * 24 * 60)::int))
FROM generate_series(1, 600);

INSERT INTO order_items (order_id, product_id, quantity, unit_price_cents)
SELECT o.id, p.id, q.qty, p.price_cents
FROM orders o
CROSS JOIN LATERAL (SELECT generate_series(1, 1 + (o.id % 3)) AS line) l
CROSS JOIN LATERAL (SELECT 1 + floor(random() * 30 + l.line * 0)::int AS pid,
                           1 + floor(random() * 2 + l.line * 0)::int AS qty) q
JOIN products p ON p.id = q.pid;

-- Collapse duplicate product lines created by the random picks.
DELETE FROM order_items a USING order_items b
WHERE a.order_id = b.order_id AND a.product_id = b.product_id AND a.id > b.id;

UPDATE orders o SET total_cents = t.total
FROM (SELECT order_id, sum(quantity * unit_price_cents) AS total FROM order_items GROUP BY order_id) t
WHERE t.order_id = o.id;

-- Status follows order age: old orders are delivered, recent ones are still paid or shipped.
UPDATE orders SET status = CASE
    WHEN created_at > now() - interval '2 days'  THEN 'PAID'
    WHEN created_at > now() - interval '6 days'  THEN 'SHIPPED'
    WHEN id % 41 = 0                             THEN 'CANCELLED'
    ELSE 'DELIVERED' END;

-- A few historical refunds so the policy/limits have something to work against.
INSERT INTO refunds (order_id, amount_cents, reason, created_by, created_at)
SELECT id, total_cents / 2, 'Item arrived damaged (partial refund)', 'support:legacy', created_at + interval '5 days'
FROM orders WHERE status = 'DELIVERED' AND id % 37 = 0;

UPDATE orders o SET refunded_cents = r.amount, status = 'PARTIALLY_REFUNDED'
FROM (SELECT order_id, sum(amount_cents) AS amount FROM refunds GROUP BY order_id) r
WHERE r.order_id = o.id;
