-- F09.16 居住実態管理・見守り
-- resident_registry に居住実態関連の 5 カラムを追加する。
-- F09.1 と同一ドメイン内の拡張であり、主キーは BIGINT を維持する（UUIDv7 化はしない）。
ALTER TABLE resident_registry
    ADD COLUMN occupancy_status VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN'
        COMMENT '居住実態区分: OWNER_OCCUPIED / RENTED_OUT / VACANT / SECONDARY_HOME / UNKNOWN',
    ADD COLUMN last_annual_review_at DATETIME(6) NULL
        COMMENT '直近の年次居住実態更新日時（annual_review_responses から派生キャッシュ）',
    ADD COLUMN annual_review_due_at DATE NULL
        COMMENT '次回年次居住実態更新の期限日',
    ADD COLUMN is_secondary_home BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'セカンドハウス・別荘扱いフラグ（通常の見守り対象から除外）',
    ADD COLUMN age_estimated TINYINT UNSIGNED NULL
        COMMENT '推定年齢（0〜200、自己申告ベース・本人開示）';

CREATE INDEX idx_resident_registry_occupancy
    ON resident_registry (occupancy_status, deleted_at);

CREATE INDEX idx_resident_registry_review_due
    ON resident_registry (annual_review_due_at);
