-- F17.2 Wave1 ②寄合後半戦: コメント（village_meetup_comments）
-- 確定した寄合に対する村人の会話。論理削除は投稿者本人＋村長/長老のみ（Service層で認可）。
-- 原則6 適用: PK = UUIDv7 BINARY(16)
-- 原則7 適用外: 全テナント横断ドメイン
--
-- 設計判断（docs/features/F17.2_village_events_activation.md §4.2.2）:
--   - meetup_id はクロスドメインではないが、原則1に従い FK は張らずインデックスのみ
--   - 論理削除（deleted_at）で原則3 に準拠

CREATE TABLE village_meetup_comments (
    id                      BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    meetup_id               BINARY(16)      NOT NULL                                COMMENT '→ village_meetups.id（同一ドメイン・FK非付与/index）',
    author_user_id          BIGINT UNSIGNED NOT NULL                                COMMENT '投稿者ユーザーID（FK 張らない・原則1）',
    body                    TEXT            NOT NULL                                COMMENT 'コメント本文',
    deleted_at              DATETIME(6)     NULL                                    COMMENT '論理削除',
    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                 BIGINT          NOT NULL DEFAULT 0                      COMMENT '楽観ロック',
    PRIMARY KEY (id),
    KEY idx_vmc_meetup_created (meetup_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='寄合コメント（F17.2 Wave1 ②寄合後半戦）';
