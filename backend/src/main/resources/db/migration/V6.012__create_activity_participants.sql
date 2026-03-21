-- F06.4: 活動参加者テーブル
CREATE TABLE activity_participants (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_result_id  BIGINT UNSIGNED NOT NULL,
    user_id             BIGINT UNSIGNED NULL,
    member_profile_id   BIGINT UNSIGNED NULL,
    display_name        VARCHAR(100) NOT NULL,
    member_number       VARCHAR(20) NULL,
    participation_type  VARCHAR(15) NOT NULL DEFAULT 'OTHER',
    minutes_played      INT UNSIGNED NULL,
    note                VARCHAR(500) NULL,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ap_activity_user (activity_result_id, user_id),
    INDEX idx_ap_user (user_id),
    INDEX idx_ap_type (activity_result_id, participation_type),
    INDEX idx_ap_member_profile (member_profile_id),
    CONSTRAINT fk_ap_result FOREIGN KEY (activity_result_id) REFERENCES activity_results (id) ON DELETE CASCADE,
    CONSTRAINT fk_ap_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
