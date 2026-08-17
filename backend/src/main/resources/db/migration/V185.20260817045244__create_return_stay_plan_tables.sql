-- F02.11 帰省・滞在予定: UUIDv7 の予定・公開先・owner lock 骨格
-- クロスドメイン FK は作らず、同一ドメイン内の plan -> visibility のみ CASCADE とする。

CREATE TABLE return_stay_plans (
    id              BINARY(16)      NOT NULL,
    owner_user_id   BIGINT UNSIGNED NOT NULL,
    plan_type       VARCHAR(20)     NOT NULL,
    is_published    BOOLEAN         NOT NULL DEFAULT FALSE,
    country_code    CHAR(2)         NOT NULL,
    prefecture_code CHAR(2)         NULL,
    region_name     VARCHAR(100)    NULL,
    timezone        VARCHAR(64)     NOT NULL,
    start_date      DATE            NOT NULL,
    end_date        DATE            NOT NULL,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT chk_rsp_plan_type CHECK (plan_type IN ('HOMECOMING', 'STAYING')),
    CONSTRAINT chk_rsp_dates CHECK (end_date >= start_date),
    CONSTRAINT chk_rsp_location CHECK (
        (country_code = 'JP' AND prefecture_code REGEXP '^(0[1-9]|[1-3][0-9]|4[0-7])$' AND region_name IS NULL)
        OR (country_code <> 'JP' AND prefecture_code IS NULL AND region_name IS NOT NULL)
    ),
    KEY idx_rsp_owner_end (owner_user_id, end_date, start_date, id),
    KEY idx_rsp_owner_updated (owner_user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE return_stay_plan_team_visibilities (
    id          BINARY(16)      NOT NULL,
    plan_id     BINARY(16)      NOT NULL,
    team_id     BIGINT UNSIGNED NOT NULL,
    created_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_rsptv_plan_team UNIQUE (plan_id, team_id),
    CONSTRAINT fk_rsptv_plan FOREIGN KEY (plan_id)
        REFERENCES return_stay_plans (id) ON DELETE CASCADE,
    KEY idx_rsptv_team_plan (team_id, plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE return_stay_plan_owner_locks (
    id              BINARY(16)      NOT NULL,
    owner_user_id   BIGINT UNSIGNED NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_rspol_owner UNIQUE (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
