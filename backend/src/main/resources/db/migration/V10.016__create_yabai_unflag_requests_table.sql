-- ヤバいやつ解除申請テーブル
CREATE TABLE yabai_unflag_requests (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id           BIGINT UNSIGNED NOT NULL,
    reason            TEXT            NOT NULL,
    status            VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    reviewed_by       BIGINT UNSIGNED NULL,
    review_note       TEXT            NULL,
    reviewed_at       DATETIME        NULL,
    next_eligible_at  DATETIME        NULL,
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_yur_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_yur_user   (user_id, created_at DESC),
    INDEX idx_yur_status (status, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
