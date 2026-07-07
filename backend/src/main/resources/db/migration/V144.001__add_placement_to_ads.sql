-- F09.19.1: ads に placement + バナー表示属性を追加する（正本 §5.2 V144.001）
-- width / height / alt_text は serve 応答（§6.2）のデータ源。既存 ads は保持していないため追加する。
-- 既存行は DEFAULT 'DASHBOARD_TILE' / NULL 許容で埋まるため既存データ番人テストは不要。
ALTER TABLE ads
    ADD COLUMN placement VARCHAR(30) NOT NULL DEFAULT 'DASHBOARD_TILE'
        COMMENT 'AdPlacement。クリエイティブはサイズが placement 依存のため ads 単位' AFTER destination_url,
    ADD COLUMN width SMALLINT UNSIGNED NULL COMMENT 'バナー幅 px（NULL: FE の placement 既定サイズ）' AFTER placement,
    ADD COLUMN height SMALLINT UNSIGNED NULL COMMENT 'バナー高さ px' AFTER width,
    ADD COLUMN alt_text VARCHAR(200) NULL COMMENT '代替テキスト（NULL: title を代用）' AFTER height;

CREATE INDEX idx_ads_placement_status ON ads (placement, status);
