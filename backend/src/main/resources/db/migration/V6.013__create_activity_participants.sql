-- F06.4: 活動参加者テーブル
CREATE TABLE activity_participants (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_result_id  BIGINT UNSIGNED NOT NULL,
    user_id             BIGINT UNSIGNED NOT NULL,
    role_label          VARCHAR(50) NULL,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ap_activity_user (activity_result_id, user_id),
    INDEX idx_ap_user (user_id, created_at DESC),
    CONSTRAINT fk_ap_result FOREIGN KEY (activity_result_id) REFERENCES activity_results (id) ON DELETE CASCADE,
    CONSTRAINT fk_ap_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
