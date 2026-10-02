-- F10.8 アクセス解析: page_view_logs（ページビュー生ログ・月次レンジパーティション）
--
-- FE ビーコンが 1 閲覧ごとに 1 行を非同期 INSERT する高頻度書き込みテーブル。
-- スケール手本は audit_logs（F10.3 / V64.001）の月次レンジパーティション。
--
-- 設計方針（docs/features/F10.8_team_org_access_analytics.md §4.2）:
--   - 主キーは UUIDv7（BINARY(16)・UuidV7Entity）。ただし MySQL のレンジパーティションは
--     パーティションキー（viewed_at）を全 UNIQUE インデックスに含める必要があるため、
--     PRIMARY KEY (id, viewed_at) の複合 PK とする（audit_logs と同方式）。
--   - クロスドメイン FK は張らない（users/teams/organizations への FK 禁止・アプリ層で整合性保証）。
--   - content_id は ID を持たない種別（PAGE 等）で 0 固定（NOT NULL 制約）。

CREATE TABLE page_view_logs (
    id           BINARY(16)      NOT NULL,
    scope_type   VARCHAR(20)     NOT NULL,
    scope_id     BIGINT UNSIGNED NOT NULL,
    content_type VARCHAR(20)     NOT NULL,
    content_id   BIGINT UNSIGNED NOT NULL DEFAULT 0,
    url          VARCHAR(512)    NOT NULL,
    title        VARCHAR(255)    NOT NULL,
    user_id      BIGINT UNSIGNED NULL,
    visitor_id   CHAR(36)        NOT NULL,
    viewed_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, viewed_at),
    INDEX idx_pvl_scope_viewed  (scope_type, scope_id, viewed_at),
    INDEX idx_pvl_scope_visitor (scope_type, scope_id, visitor_id),
    INDEX idx_pvl_scope_content (scope_type, scope_id, content_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 月次レンジパーティション（2026-07〜2029-12 + p_future）。TO_DAYS(viewed_at) で分割。
-- 手本: audit_logs V64.001。翌々月分の自動追加バッチ（§5.4）で p_future を再オーガナイズする。
ALTER TABLE page_view_logs
    PARTITION BY RANGE (TO_DAYS(viewed_at)) (
        PARTITION p_2026_07 VALUES LESS THAN (TO_DAYS('2026-08-01')),
        PARTITION p_2026_08 VALUES LESS THAN (TO_DAYS('2026-09-01')),
        PARTITION p_2026_09 VALUES LESS THAN (TO_DAYS('2026-10-01')),
        PARTITION p_2026_10 VALUES LESS THAN (TO_DAYS('2026-11-01')),
        PARTITION p_2026_11 VALUES LESS THAN (TO_DAYS('2026-12-01')),
        PARTITION p_2026_12 VALUES LESS THAN (TO_DAYS('2027-01-01')),
        PARTITION p_2027_01 VALUES LESS THAN (TO_DAYS('2027-02-01')),
        PARTITION p_2027_02 VALUES LESS THAN (TO_DAYS('2027-03-01')),
        PARTITION p_2027_03 VALUES LESS THAN (TO_DAYS('2027-04-01')),
        PARTITION p_2027_04 VALUES LESS THAN (TO_DAYS('2027-05-01')),
        PARTITION p_2027_05 VALUES LESS THAN (TO_DAYS('2027-06-01')),
        PARTITION p_2027_06 VALUES LESS THAN (TO_DAYS('2027-07-01')),
        PARTITION p_2027_07 VALUES LESS THAN (TO_DAYS('2027-08-01')),
        PARTITION p_2027_08 VALUES LESS THAN (TO_DAYS('2027-09-01')),
        PARTITION p_2027_09 VALUES LESS THAN (TO_DAYS('2027-10-01')),
        PARTITION p_2027_10 VALUES LESS THAN (TO_DAYS('2027-11-01')),
        PARTITION p_2027_11 VALUES LESS THAN (TO_DAYS('2027-12-01')),
        PARTITION p_2027_12 VALUES LESS THAN (TO_DAYS('2028-01-01')),
        PARTITION p_2028_01 VALUES LESS THAN (TO_DAYS('2028-02-01')),
        PARTITION p_2028_02 VALUES LESS THAN (TO_DAYS('2028-03-01')),
        PARTITION p_2028_03 VALUES LESS THAN (TO_DAYS('2028-04-01')),
        PARTITION p_2028_04 VALUES LESS THAN (TO_DAYS('2028-05-01')),
        PARTITION p_2028_05 VALUES LESS THAN (TO_DAYS('2028-06-01')),
        PARTITION p_2028_06 VALUES LESS THAN (TO_DAYS('2028-07-01')),
        PARTITION p_2028_07 VALUES LESS THAN (TO_DAYS('2028-08-01')),
        PARTITION p_2028_08 VALUES LESS THAN (TO_DAYS('2028-09-01')),
        PARTITION p_2028_09 VALUES LESS THAN (TO_DAYS('2028-10-01')),
        PARTITION p_2028_10 VALUES LESS THAN (TO_DAYS('2028-11-01')),
        PARTITION p_2028_11 VALUES LESS THAN (TO_DAYS('2028-12-01')),
        PARTITION p_2028_12 VALUES LESS THAN (TO_DAYS('2029-01-01')),
        PARTITION p_2029_01 VALUES LESS THAN (TO_DAYS('2029-02-01')),
        PARTITION p_2029_02 VALUES LESS THAN (TO_DAYS('2029-03-01')),
        PARTITION p_2029_03 VALUES LESS THAN (TO_DAYS('2029-04-01')),
        PARTITION p_2029_04 VALUES LESS THAN (TO_DAYS('2029-05-01')),
        PARTITION p_2029_05 VALUES LESS THAN (TO_DAYS('2029-06-01')),
        PARTITION p_2029_06 VALUES LESS THAN (TO_DAYS('2029-07-01')),
        PARTITION p_2029_07 VALUES LESS THAN (TO_DAYS('2029-08-01')),
        PARTITION p_2029_08 VALUES LESS THAN (TO_DAYS('2029-09-01')),
        PARTITION p_2029_09 VALUES LESS THAN (TO_DAYS('2029-10-01')),
        PARTITION p_2029_10 VALUES LESS THAN (TO_DAYS('2029-11-01')),
        PARTITION p_2029_11 VALUES LESS THAN (TO_DAYS('2029-12-01')),
        PARTITION p_2029_12 VALUES LESS THAN (TO_DAYS('2030-01-01')),
        PARTITION p_future  VALUES LESS THAN MAXVALUE
    );
