-- Dealer portal: 2027 margin change, Comet excluded from the stock-order margin
-- (production meeting 2026-02-18). Runs once on the 2027 terms release.

UPDATE portal.margin_rule
   SET stock_order_percent = 34
 WHERE model_family IN ('MERIDIAN', 'KESTREL');

UPDATE portal.margin_rule
   SET stock_order_percent = list_percent
 WHERE model_family = 'COMET';

INSERT INTO portal.terms_notice (effective_from, text)
VALUES ('2027-01-01', 'Stock-order margin of 34% applies to Meridian and Kestrel orders over 20 units. Comet orders carry the list margin.');

-- April 2026 price change (production meeting 2026-02-18): Kestrel 1 gravel only.
INSERT INTO portal.price (sku, effective_from, dealer_eur, retail_eur)
VALUES ('HBW-K1-M', '2026-04-01', 1450, 2099),
       ('HBW-K1-L', '2026-04-01', 1450, 2099);
