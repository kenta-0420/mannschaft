-- F05.4 アンケート・投票: survey_options テーブル
CREATE TABLE survey_options (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    question_id    BIGINT       NOT NULL,
    option_text    VARCHAR(200) NOT NULL,
    display_order  INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_survey_options_question_order (question_id, display_order),
    CONSTRAINT fk_survey_options_question FOREIGN KEY (question_id) REFERENCES survey_questions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
