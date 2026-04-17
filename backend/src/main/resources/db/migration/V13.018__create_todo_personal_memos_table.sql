-- F02.3拡張 Phase1: TODO個人メモテーブル作成
-- 1TODO × 1ユーザー = 1レコード（UPSERT運用）。本人のみ参照可能。論理削除なし（物理削除のみ）
CREATE TABLE todo_personal_memos (
    id         BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    todo_id    BIGINT UNSIGNED  NOT NULL COMMENT '対象TODOのID',
    user_id    BIGINT UNSIGNED  NOT NULL COMMENT 'メモ所有者のユーザーID',
    body       TEXT             NOT NULL COMMENT 'メモ本文（Markdown対応）',
    created_at DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_tpm_todo_user (todo_id, user_id)   COMMENT '1TODO1ユーザーにつき1メモ',

    CONSTRAINT fk_tpm_todo
        FOREIGN KEY (todo_id) REFERENCES todos(id) ON DELETE CASCADE,
    CONSTRAINT fk_tpm_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='TODO個人メモ。ユーザーごとのプライベートメモ（他メンバーからは不可視）';
