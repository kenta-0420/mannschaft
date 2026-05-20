-- F09.17 AdSegmentEvaluator Phase A: users テーブルにターゲティング用フィールドを追加

ALTER TABLE users
    ADD COLUMN gender                 TEXT        NULL     COMMENT '性別（AES-256-GCM 暗号化、任意）',
    ADD COLUMN gender_hash            VARCHAR(64) NULL     COMMENT 'gender の HMAC-SHA256（広告ターゲティング検索用）',
    ADD COLUMN prefecture_code        TEXT        NULL     COMMENT '都道府県コード（AES-256-GCM 暗号化、JIS X 0401 01〜47、任意）',
    ADD COLUMN prefecture_code_hash   VARCHAR(64) NULL     COMMENT 'prefecture_code の HMAC-SHA256（広告ターゲティング検索用）',
    ADD COLUMN city_code              TEXT        NULL     COMMENT '市区町村コード（AES-256-GCM 暗号化、JIS X 0402、任意）',
    ADD COLUMN city_code_hash         VARCHAR(64) NULL     COMMENT 'city_code の HMAC-SHA256（広告ターゲティング検索用）',
    ADD COLUMN birth_date_hash        VARCHAR(64) NULL     COMMENT 'birth_date の HMAC-SHA256（AGE_RANGE ターゲティング検索用）';

CREATE INDEX idx_users_gender_hash          ON users(gender_hash);
CREATE INDEX idx_users_prefecture_code_hash ON users(prefecture_code_hash);
CREATE INDEX idx_users_city_code_hash       ON users(city_code_hash);
CREATE INDEX idx_users_birth_date_hash      ON users(birth_date_hash);
