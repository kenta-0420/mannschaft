-- F17.2 Wave1 ②寄合後半戦: 宿題TODO（village_meetup_todos）
-- assignee_user_id NULL = 手挙げ待ち（未割当）。claim/complete/release の権限は
-- Service層で assignee 一致を検証する（設計書 §4.3 権限表）。
-- 原則6 適用: PK = UUIDv7 BINARY(16)
-- 原則7 適用外: 全テナント横断ドメイン
--
-- 設計判断（docs/features/F17.2_village_events_activation.md §4.2.3）:
--   - meetup_id はクロスドメインではないが、原則1に従い FK は張らずインデックスのみ
--   - 論理削除（deleted_at）で原則3 に準拠

CREATE TABLE village_meetup_todos (
    id                      BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    meetup_id               BINARY(16)      NOT NULL                                COMMENT '→ village_meetups.id（同一ドメイン・FK非付与/index）',
    title                   VARCHAR(200)    NOT NULL                                COMMENT '宿題のタイトル',
    assignee_user_id        BIGINT UNSIGNED NULL                                    COMMENT '担当者（NULL=手挙げ待ち・未割当。FK 張らない・原則1）',
    done_at                 DATETIME(6)     NULL                                    COMMENT '完了時刻（NULL=未完）',
    created_by              BIGINT UNSIGNED NOT NULL                                COMMENT '作成者（幹事想定・FK 張らない・原則1）',
    deleted_at              DATETIME(6)     NULL                                    COMMENT '論理削除',
    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                 BIGINT          NOT NULL DEFAULT 0                      COMMENT '楽観ロック',
    PRIMARY KEY (id),
    KEY idx_vmt_meetup (meetup_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='寄合宿題TODO（F17.2 Wave1 ②寄合後半戦）';
