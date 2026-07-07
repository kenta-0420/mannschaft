-- F09.19.1: ad_campaigns に運用型 CRUD 対応列を追加する（正本 §5.2 V144.002）
-- rate_card_id は同一 advertising ドメインのため FK 可（ON DELETE 指定なし = RESTRICT 既定。
-- 参照中カードの削除は AD_034 でアプリ層が事前拒否する）。
-- report_suspended_at は F09.19.9（通報）の列だが DTO 契約の時系列破綻を防ぐため本弾へ前倒しする
-- （.9 実装まで書き込みは発生せず常に NULL）。
ALTER TABLE ad_campaigns
    ADD COLUMN rate_card_id BIGINT UNSIGNED NULL
        COMMENT 'ad_rate_cards.id（同一 advertising ドメインのため FK 可）' AFTER daily_impression_limit,
    ADD COLUMN unit_price_snapshot DECIMAL(10,4) NULL
        COMMENT '申込時単価スナップショット（円）。NULL 時は集計日に有効な全国・全テンプレートカードで代替' AFTER rate_card_id,
    ADD COLUMN reject_reason VARCHAR(500) NULL
        COMMENT '直近の審査差戻し理由。reject 時 SET・submit 時 NULL クリア' AFTER unit_price_snapshot,
    ADD COLUMN report_suspended_at DATETIME NULL
        COMMENT '通報 3 件による自動停止時刻（書き込みは F09.19.9 実装後。それまで常に NULL）' AFTER reject_reason,
    ADD CONSTRAINT fk_ad_campaigns_rate_card FOREIGN KEY (rate_card_id) REFERENCES ad_rate_cards (id);
