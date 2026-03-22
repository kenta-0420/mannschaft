-- F10.1 フィードバック投票テーブル
CREATE TABLE feedback_votes (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    feedback_id BIGINT UNSIGNED NOT NULL,
    user_id     BIGINT UNSIGNED NOT NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_fv_feedback_user (feedback_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
