-- F09.19.9 通報 API 一式（メッセージ型/運用型両対応）
-- 正本: docs/features/F09.19_ad_slot_serving.md §5.2（V145.001 仮ラベル）/ §12
--
-- 既存データ番人: 既存 ad_user_reports 行は全て campaign_id 非 NULL のため、
-- operational_campaign_id を NULL 追加した直後の XOR CHECK
-- `(campaign_id IS NULL) != (operational_campaign_id IS NULL)` を自然に満たす
-- （FALSE != TRUE = TRUE）。よって既存データ是正は不要（CHECK 追加前の UPDATE 不要）。

-- 1) ad_user_reports: メッセージ型（campaign_id）/ 運用型（operational_campaign_id）の XOR 対応。
--    既存 FK fk_aur_campaign（ON DELETE CASCADE）は NULL 許容化後も維持される（FK は NULL 値を無視する）。
ALTER TABLE ad_user_reports
    MODIFY COLUMN campaign_id BINARY(16) NULL
        COMMENT 'ad_messaging_campaigns.id (FK CASCADE)。運用型通報時 NULL',
    ADD COLUMN operational_campaign_id BIGINT UNSIGNED NULL
        COMMENT 'F09.7 ad_campaigns.id（同一 advertising ドメインだが将来分離に備え FK なし・INDEX のみ）'
        AFTER campaign_id,
    ADD CONSTRAINT chk_aur_target CHECK (
        (campaign_id IS NULL) != (operational_campaign_id IS NULL)
    );

-- 運用型通報の自動停止判定（operational_campaign_id, status）用インデックス。
CREATE INDEX idx_aur_operational_status ON ad_user_reports (operational_campaign_id, status);

-- 2) ad_campaigns: 通報自動停止が ACTIVE→PAUSED 遷移を行ったことを記録する。
--    unsuspend（§6.1）で「自動停止で PAUSED になっていた場合のみ ACTIVE 復帰」を判定するために必要。
--    広告主自身が pause 済みの campaign が自動停止された場合は status 不変（本フラグ FALSE のまま）で、
--    unsuspend しても ACTIVE には戻さない（広告主の pause 意図を保持する）。
ALTER TABLE ad_campaigns
    ADD COLUMN report_auto_paused BOOLEAN NULL DEFAULT FALSE
        COMMENT '通報自動停止が ACTIVE→PAUSED 遷移を行った場合 TRUE。unsuspend の ACTIVE 復帰判定用（F09.19.9）'
        AFTER report_suspended_at;
