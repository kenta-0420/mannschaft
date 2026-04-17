-- F02.3拡張 Phase1: TODO共有メモ（スレッド形式）テーブル作成
-- メンバー全員が参照・投稿可能な共有メモ。引用機能付き。論理削除あり
CREATE TABLE todo_shared_memo_entries (
    id                BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    todo_id           BIGINT UNSIGNED  NOT NULL    COMMENT '対象TODOのID',
    user_id           BIGINT UNSIGNED  NOT NULL    COMMENT '投稿者ユーザーID（FK制約なし：退会後も内容を保持）',
    body              TEXT             NOT NULL    COMMENT 'メモ本文（Markdown対応）',
    quoted_entry_id   BIGINT UNSIGNED  NULL        COMMENT '引用元エントリID（自己参照。NULLの場合は引用なし）',
    created_at        DATETIME         NOT NULL    DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME         NOT NULL    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at        DATETIME         NULL        COMMENT '論理削除日時',

    PRIMARY KEY (id),
    INDEX idx_tsme_todo_created (todo_id, created_at)     COMMENT 'TODO内のメモ時系列取得',
    INDEX idx_tsme_user (user_id)                          COMMENT 'ユーザーの投稿一覧',
    INDEX idx_tsme_quoted (quoted_entry_id)                COMMENT '引用元エントリの被引用一覧',

    CONSTRAINT fk_tsme_todo
        FOREIGN KEY (todo_id) REFERENCES todos(id) ON DELETE CASCADE,
    CONSTRAINT fk_tsme_quoted_entry
        FOREIGN KEY (quoted_entry_id) REFERENCES todo_shared_memo_entries(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='TODO共有メモエントリ。メンバー全員が参照・投稿可能なスレッド形式のメモ';
