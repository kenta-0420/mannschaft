-- F05.4 アンケート・投票: survey_responses テーブル
CREATE TABLE survey_responses (
    id              BIGINT   NOT NULL AUTO_INCREMENT,
    survey_id       BIGINT   NOT NULL,
    question_id     BIGINT   NOT NULL,
    user_id         BIGINT   NOT NULL,
    option_id       BIGINT   NULL,
    text_response   TEXT     NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_survey_responses_survey_user (survey_id, user_id),
    INDEX idx_survey_responses_question (question_id),
    INDEX idx_survey_responses_option (option_id),
    CONSTRAINT fk_survey_responses_survey FOREIGN KEY (survey_id) REFERENCES surveys(id) ON DELETE CASCADE,
    CONSTRAINT fk_survey_responses_question FOREIGN KEY (question_id) REFERENCES survey_questions(id) ON DELETE CASCADE,
    CONSTRAINT fk_survey_responses_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_survey_responses_option FOREIGN KEY (option_id) REFERENCES survey_options(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
