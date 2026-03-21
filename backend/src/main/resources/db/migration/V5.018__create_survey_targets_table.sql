-- F05.4 アンケート・投票: survey_targets テーブル
CREATE TABLE survey_targets (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    survey_id   BIGINT   NOT NULL,
    user_id     BIGINT   NOT NULL,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_survey_targets_survey_user (survey_id, user_id),
    CONSTRAINT fk_survey_targets_survey FOREIGN KEY (survey_id) REFERENCES surveys(id) ON DELETE CASCADE,
    CONSTRAINT fk_survey_targets_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
