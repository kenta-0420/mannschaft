-- F09.17 AdSegmentEvaluator Phase B: AGE_RANGE セグメント用 birth_year カラム追加
-- birth_date は AES-256-GCM 暗号化のため SQL での範囲検索不可。
-- 平文の生年（SMALLINT）を別カラムで保持し INDEX を張る。
ALTER TABLE users
    ADD COLUMN birth_year SMALLINT UNSIGNED NULL
        COMMENT 'F09.17 Phase B: AGE_RANGE セグメント用。birth_date の年のみ平文保存';

CREATE INDEX idx_users_birth_year ON users (birth_year);
