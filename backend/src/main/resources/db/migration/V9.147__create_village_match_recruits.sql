-- F17.1 Phase 2: 村練習試合・審判募集
-- スポーツ系村向けの「対戦相手募集」「審判募集」「会場提供募集」の汎用テンプレ。
-- 原則6 適用: PK = UUIDv7 BINARY(16)
-- 原則7 適用外: 全テナント横断ドメイン
--
-- 設計判断:
--   - category: PRACTICE_MATCH / REFEREE / VENUE / OTHER の 4 種
--   - status: OPEN（募集中）/ CLOSED（締切）/ FULFILLED（成立）/ CANCELLED（中止）
--   - 応募承認フローはシンプル 2 段階（PENDING → ACCEPTED/REJECTED）+ 自主取消 WITHDRAWN
--   - チーム代表として投稿/応募する場合は posted_by_team_id / applicant_team_id を持つ（FK 張らない・原則1）

CREATE TABLE village_match_recruits (
    id                      BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    village_id              BINARY(16)      NOT NULL                                COMMENT 'FK → villages.id（同一ドメイン CASCADE）',
    posted_by_user_id       BIGINT UNSIGNED NOT NULL                                COMMENT '募集投稿者ユーザーID（FK 張らない・原則1）',
    posted_by_team_id       BIGINT UNSIGNED NULL                                    COMMENT 'チーム代表として投稿の場合のチームID（FK 張らない・原則1）',
    category                VARCHAR(30)     NOT NULL                                COMMENT 'PRACTICE_MATCH / REFEREE / VENUE / OTHER',
    title                   VARCHAR(100)    NOT NULL                                COMMENT '募集タイトル',
    description             TEXT            NULL                                    COMMENT '詳細説明',
    match_date              DATE            NOT NULL                                COMMENT '試合予定日',
    match_time_start        TIME            NULL                                    COMMENT '開始時刻',
    match_time_end          TIME            NULL                                    COMMENT '終了時刻',
    venue                   VARCHAR(200)    NULL                                    COMMENT '場所（自由文字列）',
    required_count          SMALLINT UNSIGNED NULL                                  COMMENT '募集人数 / チーム数',
    contact_method          VARCHAR(200)    NULL                                    COMMENT '連絡方法（自由文字列）',
    application_deadline    DATETIME(6)     NULL                                    COMMENT '応募締切（UTC）',
    status                  VARCHAR(20)     NOT NULL DEFAULT 'OPEN'                 COMMENT 'OPEN / CLOSED / FULFILLED / CANCELLED',
    deleted_at              DATETIME(6)     NULL                                    COMMENT '論理削除',
    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                 BIGINT          NOT NULL DEFAULT 0                      COMMENT '楽観ロック',
    PRIMARY KEY (id),
    KEY idx_vmr_village_status_date (village_id, status, match_date),
    KEY idx_vmr_category (category, status),
    KEY idx_vmr_match_date (match_date, status),
    KEY idx_vmr_posted_by_user (posted_by_user_id),
    CONSTRAINT fk_vmr_village FOREIGN KEY (village_id) REFERENCES villages(id) ON DELETE CASCADE,
    CONSTRAINT chk_vmr_time_range CHECK (
        match_time_start IS NULL OR match_time_end IS NULL OR match_time_end >= match_time_start
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='村練習試合・審判募集（F17.1 Phase 2）';

-- 応募テーブル（同一ドメイン CASCADE 許可）
CREATE TABLE village_match_recruit_applications (
    id                      BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    recruit_id              BINARY(16)      NOT NULL                                COMMENT 'FK → village_match_recruits.id（同一ドメイン CASCADE）',
    applicant_user_id       BIGINT UNSIGNED NOT NULL                                COMMENT '応募者ユーザーID（FK 張らない・原則1）',
    applicant_team_id       BIGINT UNSIGNED NULL                                    COMMENT 'チーム応募の場合のチームID（FK 張らない・原則1）',
    message                 TEXT            NULL                                    COMMENT '応募メッセージ',
    status                  VARCHAR(20)     NOT NULL DEFAULT 'PENDING'              COMMENT 'PENDING / ACCEPTED / REJECTED / WITHDRAWN',
    reviewed_by_user_id     BIGINT UNSIGNED NULL                                    COMMENT '審査ユーザーID（FK 張らない・原則1）',
    reviewed_at             DATETIME(6)     NULL                                    COMMENT '審査日時',
    review_comment          TEXT            NULL                                    COMMENT '審査コメント',
    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                 BIGINT          NOT NULL DEFAULT 0                      COMMENT '楽観ロック',
    PRIMARY KEY (id),
    -- 同一募集に対する同一ユーザーの同状態応募を 1 件に絞る（履歴 = status 違いは別行で残る）
    UNIQUE KEY uk_vmra_recruit_applicant_status (recruit_id, applicant_user_id, status),
    KEY idx_vmra_status (status, created_at),
    KEY idx_vmra_applicant (applicant_user_id, status),
    CONSTRAINT fk_vmra_recruit FOREIGN KEY (recruit_id) REFERENCES village_match_recruits(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='村練習試合募集への応募（F17.1 Phase 2）';
