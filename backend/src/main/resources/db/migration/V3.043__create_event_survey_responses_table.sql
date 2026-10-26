CREATE TABLE event_survey_responses (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_survey_id BIGINT UNSIGNED NOT NULL,
    user_id         BIGINT UNSIGNED NOT NULL,
    answer_text     TEXT,
    answer_options  JSON,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_esr_survey_user (event_survey_id, user_id),
    INDEX idx_esr_user_id (user_id),

    CONSTRAINT fk_esr_survey FOREIGN KEY (event_survey_id) REFERENCES event_surveys (id) ON DELETE CASCADE,
    CONSTRAINT fk_esr_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='アンケート設問への個人回答';
