-- F03.11 募集型予約: 参加者レコード (Phase 1)
-- active_subject_key は STORED 生成カラムで UNIQUE NULL 問題を回避
CREATE TABLE recruitment_participants (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    listing_id          BIGINT UNSIGNED NOT NULL,
    participant_type    VARCHAR(20)     NOT NULL,
    user_id             BIGINT UNSIGNED,
    team_id             BIGINT UNSIGNED,
    applied_by          BIGINT UNSIGNED,
    status              VARCHAR(20)     NOT NULL DEFAULT 'APPLIED',
    waitlist_position   INT UNSIGNED,
    note                VARCHAR(500),
    applied_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status_changed_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    cancelled_by        BIGINT UNSIGNED,
    deleted_at          DATETIME,
    -- 生成カラム: 同じ参加者の重複申込を UNIQUE で防止 (NULL 問題回避)
    -- UNIX_TIMESTAMP() は非決定性のため使用不可、DATE_FORMAT で代替
    active_subject_key  VARCHAR(100)    GENERATED ALWAYS AS (
        CONCAT(
            participant_type, '-',
            COALESCE(CAST(user_id AS CHAR), CAST(team_id AS CHAR)), '-',
            COALESCE(DATE_FORMAT(deleted_at, '%Y%m%d%H%i%s'), 'active')
        )
    ) STORED NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_rp_listing
        FOREIGN KEY (listing_id) REFERENCES recruitment_listings (id) ON DELETE CASCADE,
    CONSTRAINT fk_rp_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rp_team
        FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rp_applied_by
        FOREIGN KEY (applied_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_rp_cancelled_by
        FOREIGN KEY (cancelled_by) REFERENCES users (id) ON DELETE SET NULL,
    UNIQUE KEY uk_rp_listing_subject (listing_id, active_subject_key),
    INDEX idx_rp_listing_status (listing_id, status, applied_at),
    INDEX idx_rp_user_status (user_id, status, applied_at),
    INDEX idx_rp_team_status (team_id, status, applied_at),
    INDEX idx_rp_waitlist (listing_id, status, waitlist_position),
    -- participant_type と user_id/team_id の整合 (MySQL 8.0.16+)
    CONSTRAINT chk_rp_subject CHECK (
        (participant_type = 'USER' AND user_id IS NOT NULL AND team_id IS NULL) OR
        (participant_type = 'TEAM' AND team_id IS NOT NULL AND user_id IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
