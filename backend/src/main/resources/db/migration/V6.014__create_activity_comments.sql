-- F06.4: 活動記録コメントテーブル
CREATE TABLE activity_comments (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_result_id BIGINT UNSIGNED NOT NULL,
    user_id            BIGINT UNSIGNED NULL,
    body               TEXT NOT NULL,
    created_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at         DATETIME NULL,
    PRIMARY KEY (id),
    INDEX idx_ac_activity (activity_result_id, created_at),
    CONSTRAINT fk_ac_result FOREIGN KEY (activity_result_id) REFERENCES activity_results (id) ON DELETE CASCADE,
    CONSTRAINT fk_ac_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
