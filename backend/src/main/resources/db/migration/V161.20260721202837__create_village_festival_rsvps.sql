-- F17.2 Wave2 ③お祭りの参加レイヤー: 参加表明（village_festival_rsvps）
-- 祭に対し村人が自分の参加表明（GOING/MAYBE）を upsert する。
-- 原則6 適用: PK = UUIDv7 BINARY(16)
-- 原則7 適用外: 全テナント横断ドメイン
--
-- 設計判断（docs/features/F17.2_village_events_activation.md §5.2）:
--   - festival_id はクロスドメインではないが、原則1に従い FK は張らずインデックスのみ
--   - (festival_id, user_id) UNIQUE で「1祭×1村人=1行」を DB レベルで保証（upsert）
--   - ABSENT を持たない（不参加=無回答・欠席率を構造的に作れない・§10 ガードレール）
--   - role_label は役割の自由記述ラベル（例「出店係」「受付」・NULL=役割なし・§5.3）

CREATE TABLE village_festival_rsvps (
    id                      BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    festival_id             BINARY(16)      NOT NULL                                COMMENT '→ village_festivals.id（同一ドメイン・FK非付与/index）',
    user_id                 BIGINT UNSIGNED NOT NULL                                COMMENT '参加表明した村人（FK 張らない・原則1）',
    status                  VARCHAR(20)     NOT NULL                                COMMENT 'GOING / MAYBE（ABSENT なし）',
    role_label              VARCHAR(60)     NULL                                    COMMENT '役割の自由記述ラベル（NULL=役割なし・§5.3）',
    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                 BIGINT          NOT NULL DEFAULT 0                      COMMENT '楽観ロック',
    PRIMARY KEY (id),
    UNIQUE KEY uk_vfr_festival_user (festival_id, user_id),
    KEY idx_vfr_festival (festival_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='お祭りの参加表明RSVP（F17.2 Wave2 ③）';
