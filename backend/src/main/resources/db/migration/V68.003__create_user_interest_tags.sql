-- F09.17 AdSegmentEvaluator Phase A: user_interest_tags テーブル新規作成
-- 主キーは UUIDv7（CLAUDE.md 原則 6 準拠: 新規テーブルは UUIDv7）

CREATE TABLE user_interest_tags (
    id              CHAR(36)        NOT NULL,
    user_id         BIGINT UNSIGNED NOT NULL,
    tag             VARCHAR(50)     NOT NULL COMMENT 'タグ文字列（小文字英数字・アンダースコア）',
    tag_hash        VARCHAR(64)     NOT NULL COMMENT 'tag の HMAC-SHA256（広告ターゲティング検索用）',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_user_interest_tag (user_id, tag),
    INDEX idx_uit_tag_hash (tag_hash),
    INDEX idx_uit_user_id  (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT 'ユーザーの興味・関心タグ（広告ターゲティング用）';
