-- F17.1 Phase 2: 村歳時記カレンダー（年中行事）
-- 「桃の節句」「七夕」「年越し」など、村ごとの年中行事を登録する。
-- 原則6 適用: PK = UUIDv7 BINARY(16)
-- 原則7 適用外: 全テナント横断ドメイン（村 = 組織非依存）
--
-- 設計判断:
--   - RFC 5545 RRULE は導入せず、簡易な is_annual_recurring のみで対応（毎年繰返 or 単発の二択）
--   - 複雑な繰返ルール（毎週/第○曜/旧暦 等）は Phase 3 へ繰越
--   - event_date は「基準日」。is_annual_recurring=TRUE のときは年部分を無視し月日のみで判定
--   - event_end_date は複数日イベント（例: 夏祭り 8/13〜8/15）用。NULL なら単日

CREATE TABLE village_calendar_events (
    id                      BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    village_id              BINARY(16)      NOT NULL                                COMMENT 'FK → villages.id（同一ドメイン CASCADE）',
    title                   VARCHAR(100)    NOT NULL                                COMMENT 'イベント名',
    description             TEXT            NULL                                    COMMENT '説明',
    event_date              DATE            NOT NULL                                COMMENT '基準日（is_annual_recurring=TRUE 時は年無視・月日のみ意味あり）',
    event_end_date          DATE            NULL                                    COMMENT '複数日イベントの終了日（NULL = 単日）',
    is_annual_recurring     BOOLEAN         NOT NULL DEFAULT TRUE                   COMMENT '毎年繰返すか（TRUE: 年中行事 / FALSE: 単発）',
    icon_emoji              VARCHAR(20)     NULL                                    COMMENT '表示絵文字（🌸 🎋 ⛄ など）',
    color_hex               CHAR(7)         NULL                                    COMMENT 'カレンダー表示色 #RRGGBB',
    created_by_user_id      BIGINT UNSIGNED NOT NULL                                COMMENT '作成者ユーザーID（FK 張らない・原則1）',
    deleted_at              DATETIME(6)     NULL                                    COMMENT '論理削除',
    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                 BIGINT          NOT NULL DEFAULT 0                      COMMENT '楽観ロック',
    PRIMARY KEY (id),
    KEY idx_vce_village_date (village_id, event_date, deleted_at),
    KEY idx_vce_recurring (is_annual_recurring, event_date),
    CONSTRAINT fk_vce_village FOREIGN KEY (village_id) REFERENCES villages(id) ON DELETE CASCADE,
    CONSTRAINT chk_vce_event_period CHECK (event_end_date IS NULL OR event_end_date >= event_date),
    CONSTRAINT chk_vce_color_hex CHECK (color_hex IS NULL OR color_hex REGEXP '^#[0-9A-Fa-f]{6}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='村歳時記カレンダー（F17.1 Phase 2）';
