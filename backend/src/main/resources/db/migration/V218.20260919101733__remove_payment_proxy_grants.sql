ALTER TABLE member_payments DROP COLUMN payment_proxy_grant_id;
ALTER TABLE membership_subscriptions DROP COLUMN payment_proxy_grant_id;
DROP TABLE payment_proxy_grants;
