-- F17.1 Phase 3-β: 寄合（村人同士のオフ会・集まり）の調整機能
-- 候補日複数提示 → 投票（行ける/たぶん/行けない）→ 確定日決定 のフロー。
-- 原則6 適用: PK = UUIDv7 BINARY(16)
-- 原則7 適用外: 全テナント横断ドメイン
--
-- 設計判断:
--   - village_meetups: 寄合の本体。status は PLANNING（投票中）→ CONFIRMED（確定）/ CANCELLED（中止）
--   - village_meetup_candidate_dates: 候補日。寄合 1 件あたり複数日提示
--   - village_meetup_votes: 投票。1 ユーザー × 1 候補日で UNIQUE
--   - クロスドメインFK は張らない（organizer_user_id / voter_user_id は ID のみ）

CREATE TABLE village_meetups (
    id                      BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    village_id              BINARY(16)      NOT NULL                                COMMENT 'FK → villages.id（同一ドメイン CASCADE）',
    title                   VARCHAR(200)    NOT NULL                                COMMENT '寄合のタイトル',
    description             TEXT            NULL                                    COMMENT '寄合の説明',
    organizer_user_id       BIGINT UNSIGNED NOT NULL                                COMMENT '幹事ユーザーID（FK 張らない・原則1）',
    status                  VARCHAR(20)     NOT NULL DEFAULT 'PLANNING'             COMMENT 'PLANNING / CONFIRMED / CANCELLED',
    confirmed_date          DATE            NULL                                    COMMENT '確定日（CONFIRMED 時のみセット）',
    location                VARCHAR(300)    NULL                                    COMMENT '集合場所（任意）',
    deleted_at              DATETIME(6)     NULL                                    COMMENT '論理削除',
    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                 BIGINT          NOT NULL DEFAULT 0                      COMMENT '楽観ロック',
    PRIMARY KEY (id),
    KEY idx_vm_village (village_id, status, deleted_at),
    CONSTRAINT fk_vm_village FOREIGN KEY (village_id) REFERENCES villages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='寄合本体（F17.1 Phase 3-β）';

CREATE TABLE village_meetup_candidate_dates (
    id                      BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    meetup_id               BINARY(16)      NOT NULL                                COMMENT 'FK → village_meetups.id（同一ドメイン CASCADE）',
    candidate_date          DATE            NOT NULL                                COMMENT '候補日',
    sort_order              SMALLINT UNSIGNED NOT NULL DEFAULT 0                    COMMENT '表示順',
    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_vmcd_meetup_date (meetup_id, candidate_date),
    CONSTRAINT fk_vmcd_meetup FOREIGN KEY (meetup_id) REFERENCES village_meetups(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='寄合候補日（F17.1 Phase 3-β）';

CREATE TABLE village_meetup_votes (
    id                      BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    candidate_date_id       BINARY(16)      NOT NULL                                COMMENT 'FK → village_meetup_candidate_dates.id（同一ドメイン CASCADE）',
    voter_user_id           BIGINT UNSIGNED NOT NULL                                COMMENT '投票者ユーザーID（FK 張らない・原則1）',
    vote_type               VARCHAR(20)     NOT NULL                                COMMENT 'AVAILABLE / MAYBE / UNAVAILABLE',
    voted_at                DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_vmv (candidate_date_id, voter_user_id),
    CONSTRAINT fk_vmv_date FOREIGN KEY (candidate_date_id) REFERENCES village_meetup_candidate_dates(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='寄合投票（F17.1 Phase 3-β）';
