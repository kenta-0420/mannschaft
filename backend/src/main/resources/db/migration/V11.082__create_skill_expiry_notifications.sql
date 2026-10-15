CREATE TABLE skill_expiry_notifications (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    member_skill_id BIGINT UNSIGNED NOT NULL,
    notification_type VARCHAR(20) NOT NULL,
    sent_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_sen_member_skill (member_skill_id),
    CONSTRAINT uq_sen_skill_type UNIQUE (member_skill_id, notification_type),
    CONSTRAINT chk_sen_notification_type CHECK (notification_type IN ('DAYS_30', 'DAYS_7')),
    CONSTRAINT fk_sen_member_skill FOREIGN KEY (member_skill_id) REFERENCES member_skills(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
