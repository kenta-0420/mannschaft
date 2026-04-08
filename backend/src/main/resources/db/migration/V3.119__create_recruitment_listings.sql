-- F03.11 募集型予約: 募集枠メインテーブル (Phase 1)
-- 注: cancellation_policy_id カラムは作成するが FK は付けない (V3.127 で ALTER ADD FK)
CREATE TABLE recruitment_listings (
    id                          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type                  VARCHAR(20)     NOT NULL,
    scope_id                    BIGINT UNSIGNED NOT NULL,
    category_id                 BIGINT UNSIGNED NOT NULL,
    subcategory_id              BIGINT UNSIGNED,
    template_id                 BIGINT UNSIGNED,
    title                       VARCHAR(100)    NOT NULL,
    description                 TEXT,
    participation_type          VARCHAR(20)     NOT NULL,
    start_at                    DATETIME        NOT NULL,
    end_at                      DATETIME        NOT NULL,
    application_deadline        DATETIME        NOT NULL,
    auto_cancel_at              DATETIME        NOT NULL,
    capacity                    INT UNSIGNED    NOT NULL,
    min_capacity                INT UNSIGNED    NOT NULL,
    confirmed_count             INT UNSIGNED    NOT NULL DEFAULT 0,
    waitlist_count              INT UNSIGNED    NOT NULL DEFAULT 0,
    waitlist_max                INT UNSIGNED    NOT NULL DEFAULT 100,
    payment_enabled             BOOLEAN         NOT NULL DEFAULT FALSE,
    price                       INT UNSIGNED,
    visibility                  VARCHAR(20)     NOT NULL DEFAULT 'SCOPE_ONLY',
    status                      VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    location                    VARCHAR(200),
    reservation_line_id         BIGINT UNSIGNED,
    image_url                   VARCHAR(500),
    cancellation_policy_id      BIGINT UNSIGNED,
    created_by                  BIGINT UNSIGNED NOT NULL,
    cancelled_at                DATETIME,
    cancelled_by                BIGINT UNSIGNED,
    cancelled_reason            VARCHAR(200),
    participant_count_cache     INT UNSIGNED    NOT NULL DEFAULT 0,
    next_waitlist_position      INT UNSIGNED    NOT NULL DEFAULT 1,
    created_at                  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at                  DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT fk_rl_category
        FOREIGN KEY (category_id) REFERENCES recruitment_categories (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rl_subcategory
        FOREIGN KEY (subcategory_id) REFERENCES recruitment_subcategories (id) ON DELETE SET NULL,
    CONSTRAINT fk_rl_reservation_line
        FOREIGN KEY (reservation_line_id) REFERENCES reservation_lines (id) ON DELETE SET NULL,
    CONSTRAINT fk_rl_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rl_cancelled_by
        FOREIGN KEY (cancelled_by) REFERENCES users (id) ON DELETE SET NULL,
    -- 注: fk_rl_cancellation_policy は V3.127 で後付け
    -- 注: fk_rl_template は V3.118 (Phase 3) で後付け
    INDEX idx_rl_scope_status (scope_type, scope_id, status, start_at),
    INDEX idx_rl_category_search (category_id, status, visibility, start_at),
    INDEX idx_rl_line_overlap (reservation_line_id, start_at, end_at),
    INDEX idx_rl_auto_cancel (status, auto_cancel_at),
    INDEX idx_rl_completion (status, end_at),
    INDEX idx_rl_created_by (created_by),
    -- CHECK 制約 (MySQL 8.0.16+ で有効。Service 層でも防御的二重検証)
    CONSTRAINT chk_rl_capacity         CHECK (min_capacity <= capacity),
    CONSTRAINT chk_rl_deadline_lt_start CHECK (application_deadline < start_at),
    CONSTRAINT chk_rl_auto_cancel_le_deadline CHECK (auto_cancel_at <= application_deadline),
    CONSTRAINT chk_rl_start_lt_end     CHECK (start_at < end_at),
    CONSTRAINT chk_rl_waitlist_max     CHECK (waitlist_max <= 1000),
    CONSTRAINT chk_rl_payment_price    CHECK (NOT (payment_enabled = TRUE AND price IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
