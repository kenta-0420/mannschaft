-- F17.2 Wave1 ④歳時記×村史の年輪（village_calendar_event_logs）
-- 歳時記（年中行事）に、その年ごとの「様子」（写真・一言メモ）を積む。
-- 1歳時記×1年に複数件を許す（UNIQUE を張らない・設計書 §6.3）。
-- 原則6 適用: PK = UUIDv7 BINARY(16)
-- 原則7 適用外: 全テナント横断ドメイン
--
-- 設計判断（docs/features/F17.2_village_events_activation.md §6.2）:
--   - calendar_event_id はクロスドメインではないが、原則1に従い FK は張らずインデックスのみ
--   - `year` は MySQL 予約語のためバッククォート必須（memory: MySQL 8.0 予約語カラム名）
--   - 論理削除（deleted_at）で原則3 に準拠
--   - 写真は既存 R2/MediaUrlResolver 方式（photo_r2_key）に従う

CREATE TABLE village_calendar_event_logs (
    id                      BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    calendar_event_id       BINARY(16)      NOT NULL                                COMMENT '→ village_calendar_events.id（同一ドメイン・FK非付与/index）',
    `year`                  SMALLINT        NOT NULL                                COMMENT '記録対象の西暦年（例 2026）',
    photo_r2_key            VARCHAR(255)    NULL                                    COMMENT '写真（R2キー・MediaUrlResolver で署名URL化）',
    note                    VARCHAR(300)    NULL                                    COMMENT '一言メモ',
    created_by_user_id      BIGINT UNSIGNED NOT NULL                                COMMENT '記録者（FK 張らない・原則1）',
    deleted_at              DATETIME(6)     NULL                                    COMMENT '論理削除',
    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                 BIGINT          NOT NULL DEFAULT 0                      COMMENT '楽観ロック',
    PRIMARY KEY (id),
    KEY idx_vcel_event_year (calendar_event_id, `year`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='歳時記×村史の年輪（F17.2 Wave1 ④）';
