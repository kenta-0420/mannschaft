-- F09.19.5 (Expand): ad_campaigns を advertiser_organization_id 直結から advertiser_account_id 直結へ移行する第1段。
-- 正本: docs/features/F09.19_ad_slot_serving.md §5.2（V144.005 相当。採番は origin/main 再確認により V147 に確定）。
-- 背景: advertiser_organization_id は organization 直結のままで scope 化されておらず、チーム広告主が
--       運用型キャンペーンを構造的に持てない。advertiser_accounts は scope 化済み（V67.026 Contract 完了）のため、
--       advertiser_account_id 直結に付け替えてチーム対応とドメイン境界是正を完了させる。
--       advertiser_accounts.id は同一 advertising ドメインのため FK 可。

ALTER TABLE ad_campaigns
    ADD COLUMN advertiser_account_id BIGINT UNSIGNED NULL
        COMMENT 'advertiser_accounts.id（同一 advertising ドメインのため FK 可）' AFTER advertiser_organization_id;

-- backfill: scope_type='ORGANIZATION' かつ scope_id=advertiser_organization_id の未削除アカウントへ紐付け
UPDATE ad_campaigns c
    JOIN advertiser_accounts a
        ON a.scope_type = 'ORGANIZATION' AND a.scope_id = c.advertiser_organization_id AND a.deleted_at IS NULL
    SET c.advertiser_account_id = a.id;

-- NOT NULL 昇格 + FK。backfill に一致しない orphan 行（論理削除済み広告主等）が残ると本 ALTER は
-- NOT NULL 昇格で失敗する（前提条件番人。適用前提: orphan 0 件。事前に account 復元 or キャンペーン削除で解消）。
ALTER TABLE ad_campaigns
    MODIFY COLUMN advertiser_account_id BIGINT UNSIGNED NOT NULL
        COMMENT 'advertiser_accounts.id（同一 advertising ドメインのため FK 可）',
    ADD CONSTRAINT fk_ad_campaigns_advertiser_account
        FOREIGN KEY (advertiser_account_id) REFERENCES advertiser_accounts (id);

CREATE INDEX idx_ad_campaigns_account_status ON ad_campaigns (advertiser_account_id, status);
