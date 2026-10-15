-- F02.6 お知らせウィジェット: announcement_read_status テーブル作成
-- ユーザーごとのお知らせ既読トラッキング。未読バッジ・既読後の薄表示 UX で使用。
CREATE TABLE announcement_read_status (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    announcement_feed_id BIGINT UNSIGNED NOT NULL COMMENT 'お知らせフィードID（CASCADE 削除）',
    user_id              BIGINT UNSIGNED NOT NULL COMMENT '既読したユーザーID（CASCADE 削除）',
    read_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '既読日時',

    PRIMARY KEY (id),

    -- 重複既読防止
    UNIQUE KEY uq_ars_feed_user (announcement_feed_id, user_id),

    -- ユーザー別既読履歴取得用
    INDEX idx_ars_user (user_id, read_at DESC)
        COMMENT 'ユーザー別既読履歴取得用',

    -- announcement_feeds へのFK（お知らせ削除時に既読レコードも CASCADE 削除）
    CONSTRAINT fk_ars_feed FOREIGN KEY (announcement_feed_id) REFERENCES announcement_feeds (id) ON DELETE CASCADE,

    -- users へのFK（ユーザー削除時に既読レコードも CASCADE 削除）
    CONSTRAINT fk_ars_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='お知らせ既読管理。ユーザーごとのお知らせ既読トラッキング（保持期間: 90日）';
