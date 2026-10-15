-- F17.2 Wave1 ②寄合後半戦: 出欠（village_meetup_attendances）
-- 確定（CONFIRMED）した寄合に対し、村人が自分の出欠（GOING/MAYBE/ABSENT）を upsert する。
-- 原則6 適用: PK = UUIDv7 BINARY(16)
-- 原則7 適用外: 全テナント横断ドメイン
--
-- 設計判断（docs/features/F17.2_village_events_activation.md §4.2.1）:
--   - meetup_id はクロスドメインではないが、原則1に従い FK は張らずインデックスのみ
--   - (meetup_id, user_id) UNIQUE で「1寄合×1村人=1行」を DB レベルで保証（upsert）
--   - 寄合は実務調整機能のため ABSENT を持つ（祭 RSVP との非対称・§10.1）

CREATE TABLE village_meetup_attendances (
    id                      BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    meetup_id               BINARY(16)      NOT NULL                                COMMENT '→ village_meetups.id（同一ドメイン・FK非付与/index）',
    user_id                 BIGINT UNSIGNED NOT NULL                                COMMENT '出欠を答えた村人（FK 張らない・原則1）',
    status                  VARCHAR(20)     NOT NULL                                COMMENT 'GOING / MAYBE / ABSENT',
    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                 BIGINT          NOT NULL DEFAULT 0                      COMMENT '楽観ロック',
    PRIMARY KEY (id),
    UNIQUE KEY uk_vma_meetup_user (meetup_id, user_id),
    KEY idx_vma_meetup (meetup_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='寄合出欠（F17.2 Wave1 ②寄合後半戦）';
