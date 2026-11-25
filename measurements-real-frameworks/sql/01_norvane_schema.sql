DROP DATABASE IF EXISTS norvane;
CREATE DATABASE norvane;
\c norvane
CREATE TABLE customer (
  id          bigint PRIMARY KEY,
  full_name   text NOT NULL,
  segment     text NOT NULL,
  city        text NOT NULL
);
CREATE TABLE account (
  id          bigint PRIMARY KEY,
  customer_id bigint NOT NULL REFERENCES customer(id),
  iban        text NOT NULL,
  balance_cents bigint NOT NULL
);
CREATE TABLE merchant (
  id          bigint PRIMARY KEY,
  name        text NOT NULL,
  category    text NOT NULL
);
CREATE TABLE card_transaction (
  id          bigint PRIMARY KEY,
  account_id  bigint NOT NULL,
  merchant_id bigint NOT NULL,
  booked_at   timestamp NOT NULL,
  amount_cents bigint NOT NULL,
  label       text NOT NULL
);
CREATE TABLE scheduled_payment (
  id          bigint PRIMARY KEY,
  account_id  bigint NOT NULL,
  due_date    date NOT NULL,
  amount_cents bigint NOT NULL,
  beneficiary text NOT NULL,
  status      text NOT NULL
);
INSERT INTO customer SELECT g, 'Customer ' || g,
  (ARRAY['STANDARD','PREMIUM','YOUTH','BUSINESS'])[1 + g % 4], (ARRAY['Paris','Lyon','Lille','Nantes','Créteil'])[1 + g % 5]
  FROM generate_series(1, 200000) g;
INSERT INTO account SELECT g, 1 + (g % 200000), 'FR76' || lpad(g::text, 23, '0'), (g::bigint * 7919) % 5000000
  FROM generate_series(1, 300000) g;
INSERT INTO merchant SELECT g, 'Merchant ' || g, (ARRAY['GROCERY','FUEL','TRAVEL','RESTAURANT','ONLINE'])[1 + g % 5]
  FROM generate_series(1, 20000) g;
INSERT INTO card_transaction SELECT g, 1 + (g * 31) % 300000, 1 + (g * 17) % 20000,
  timestamp '2026-01-01' + (g % 26000000) * interval '1 second', 100 + (g * 13) % 20000, 'CARD PAYMENT ' || g
  FROM generate_series(1, 6000000) g;
INSERT INTO scheduled_payment SELECT g, 1 + (g * 7) % 300000, date '2026-11-01' + (g % 60),
  1000 + (g * 11) % 200000, 'Beneficiary ' || (g % 5000), CASE WHEN g % 10 = 0 THEN 'CANCELLED' ELSE 'PLANNED' END
  FROM generate_series(1, 1500000) g;
CREATE INDEX account_customer_idx ON account(customer_id);
CREATE INDEX card_tx_account_idx ON card_transaction(account_id, booked_at DESC);
CREATE INDEX scheduled_account_idx ON scheduled_payment(account_id, due_date);
VACUUM ANALYZE;

CREATE TABLE fee_posting (id bigint PRIMARY KEY, account_id bigint, amount_cents bigint, label text);
CREATE TABLE monthly_statement (account_id bigint, month text, balance_cents bigint,
                                card_total_cents bigint, interest_cents bigint, lines int);
CREATE TABLE notification_log (event_key bigint, account_id bigint, amount_cents bigint);
