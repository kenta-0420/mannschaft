-- F02.5 行動メモ タグマスタテーブル
-- ユーザー個人の名前空間。他人と共有しない。
-- Phase 1 ではテーブルのみ先行作成し、Service / Controller は Phase 4 で実装する。
CREATE TABLE action_memo_tags (
    id         BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    user_id    BIGINT UNSIGNED  NOT NULL COMMENT '所有ユーザー',
    name       VARCHAR(50)      NOT NULL COMMENT 'タグ名',
    color      VARCHAR(7)       NULL COMMENT '表示色（HEX）。NULL = デフォルト色',
    sort_order SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '並び順',
    created_at DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME         NULL COMMENT '論理削除日時',
    PRIMARY KEY (id),
    CONSTRAINT fk_action_memo_tags_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    UNIQUE KEY uq_amt_user_name (user_id, name, deleted_at),
    INDEX idx_amt_user_sort (user_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
