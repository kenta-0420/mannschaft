-- Phase 3-B: chat_messages アーカイブテーブル作成
-- メイン chat_messages の FK/FULLTEXT を維持したまま旧データを退避する。
-- アーカイブテーブルは FK・FULLTEXT なし（参照整合性はアプリ層で保証）。
CREATE TABLE chat_messages_archive (
    id                BIGINT UNSIGNED  NOT NULL,
    channel_id        BIGINT UNSIGNED  NOT NULL,
    sender_id         BIGINT UNSIGNED,
    parent_id         BIGINT UNSIGNED,
    body              TEXT             NOT NULL,
    forwarded_from_id BIGINT UNSIGNED,
    is_edited         BOOLEAN          NOT NULL DEFAULT FALSE,
    is_system         BOOLEAN          NOT NULL DEFAULT FALSE,
    scheduled_at      DATETIME,
    reply_count       INT              NOT NULL DEFAULT 0,
    reaction_count    INT              NOT NULL DEFAULT 0,
    is_pinned         BOOLEAN          NOT NULL DEFAULT FALSE,
    created_at        DATETIME         NOT NULL,
    updated_at        DATETIME         NOT NULL,
    deleted_at        DATETIME,
    archived_at       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '移送日時',
    PRIMARY KEY (id),
    INDEX idx_arch_msg_channel_created (channel_id, created_at DESC),
    INDEX idx_arch_msg_sender_created  (sender_id, created_at DESC),
    INDEX idx_arch_msg_created_at      (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='チャットメッセージアーカイブ（6か月超）';
