-- F17.1 Phase 2: 村お祭り（期間限定イベント枠）
-- 期間付き notice として動作し、期間中はカバー/UI バッジを差替える。
-- 原則6 適用: PK = UUIDv7 BINARY(16)
-- 原則7 適用外: 全テナント横断ドメイン
--
-- 設計判断:
--   - タイムゾーンは UTC 固定（Phase 2）。村ローカル TZ 対応は Phase 3
--   - status は SCHEDULED（開始前）→ ACTIVE（期間中）→ ENDED（終了）/ CANCELLED（中止）
--   - 自動状態遷移は別途バッチで実装予定（idx_vf_active_period を利用）

CREATE TABLE village_festivals (
    id                      BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    village_id              BINARY(16)      NOT NULL                                COMMENT 'FK → villages.id（同一ドメイン CASCADE）',
    title                   VARCHAR(100)    NOT NULL                                COMMENT 'お祭り名',
    description             TEXT            NULL                                    COMMENT 'お祭り紹介文',
    starts_at               DATETIME(6)     NOT NULL                                COMMENT '開始（UTC）',
    ends_at                 DATETIME(6)     NOT NULL                                COMMENT '終了（UTC）',
    banner_r2_key           VARCHAR(255)    NULL                                    COMMENT 'バナー画像 R2 キー',
    theme_color_hex         CHAR(7)         NULL                                    COMMENT 'テーマ色 #RRGGBB',
    status                  VARCHAR(20)     NOT NULL DEFAULT 'SCHEDULED'            COMMENT 'SCHEDULED / ACTIVE / ENDED / CANCELLED',
    created_by_user_id      BIGINT UNSIGNED NOT NULL                                COMMENT '作成者ユーザーID（FK 張らない・原則1）',
    deleted_at              DATETIME(6)     NULL                                    COMMENT '論理削除',
    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                 BIGINT          NOT NULL DEFAULT 0                      COMMENT '楽観ロック',
    PRIMARY KEY (id),
    KEY idx_vf_village_status (village_id, status, ends_at),
    KEY idx_vf_active_period (status, ends_at) COMMENT '自動状態遷移バッチ用',
    KEY idx_vf_period (starts_at, ends_at),
    CONSTRAINT fk_vf_village FOREIGN KEY (village_id) REFERENCES villages(id) ON DELETE CASCADE,
    CONSTRAINT chk_vf_period CHECK (ends_at > starts_at),
    CONSTRAINT chk_vf_theme_color CHECK (theme_color_hex IS NULL OR theme_color_hex REGEXP '^#[0-9A-Fa-f]{6}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='村お祭り（F17.1 Phase 2）';
