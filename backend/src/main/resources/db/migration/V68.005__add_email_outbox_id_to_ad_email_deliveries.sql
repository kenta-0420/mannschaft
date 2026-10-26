-- F09.18 Phase 18-f: ad_email_deliveries ↔ email_outbox 双方向トレース
-- email_outbox.source_event_id = ad_email_delivery.id (既存)
-- ad_email_deliveries.email_outbox_id = email_outbox.id (本マイグレーションで追加)
ALTER TABLE ad_email_deliveries
    ADD COLUMN email_outbox_id BINARY(16) NULL
        COMMENT 'F09.18 email_outbox.id — 双方向トレース用 (FK なし、クロスドメイン原則1準拠)'
        AFTER direct_mail_recipient_id;

CREATE INDEX idx_aed_email_outbox ON ad_email_deliveries (email_outbox_id);
