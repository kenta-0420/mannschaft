-- F17.1 Phase 1: 井戸端会議の日次スレッドテーブル
-- 発言自体は chat_messages に格納される。本テーブルは日付集約ビュー。
-- chat_channel_id は別ドメインゆえ FK 張らない（原則1）

CREATE TABLE village_lobby_daily_threads (
    id                       BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    village_id               BINARY(16)      NOT NULL,
    thread_date              DATE            NOT NULL                                COMMENT 'スレッドの日付（村のローカル日）',
    chat_channel_id          BIGINT UNSIGNED NOT NULL                                COMMENT '対応するチャットチャンネル（FK 張らない / 原則1）',
    summary                  TEXT            NULL                                    COMMENT 'AI による日次サマリ（Phase 2 以降）',
    message_count_cache      BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at               DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at               DATETIME(6)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vldt_village_date (village_id, thread_date),
    KEY idx_vldt_date (thread_date),
    CONSTRAINT fk_vldt_village FOREIGN KEY (village_id) REFERENCES villages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='井戸端会議の日次スレッド（F17.1）';
