-- F09.17 Phase 11-a: announcement_feeds に広告フラグ列追加
-- scope_type は元から VARCHAR(20) のため ENUM 拡張は不要。
-- 「広告」ラベル必須化 (景表法) のため bool 列で区別する。
-- 列追加と INDEX 追加のみ。既存クエリの絞り込み変更は別 PR で扱う。
ALTER TABLE announcement_feeds
    ADD COLUMN is_advertisement BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'F09.17 広告主キャンペーン由来フラグ',
    ADD INDEX idx_af_is_advertisement (is_advertisement, scope_type);

-- scope_type は VARCHAR(20) であり 'ADVERTISER_AD' 値は別途アプリ層で扱う。
-- 列コメントを再整備して許容値を追記する。
ALTER TABLE announcement_feeds
    MODIFY COLUMN scope_type VARCHAR(20) NOT NULL
        COMMENT '表示スコープ種別: TEAM / ORGANIZATION / ADVERTISER_AD';
