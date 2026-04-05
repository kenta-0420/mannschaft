-- F05.4 アンケート・投票: survey_questions テーブル
CREATE TABLE survey_questions (
    id               BIGINT UNSIGNED       NOT NULL AUTO_INCREMENT,
    survey_id        BIGINT UNSIGNED       NOT NULL,
    question_type    VARCHAR(20)  NOT NULL,
    question_text    VARCHAR(500) NOT NULL,
    is_required      BOOLEAN      NOT NULL DEFAULT TRUE,
    display_order    INT          NOT NULL DEFAULT 0,
    max_selections   INT          NULL,
    scale_min        INT          NULL,
    scale_max        INT          NULL,
    scale_min_label  VARCHAR(50)  NULL,
    scale_max_label  VARCHAR(50)  NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_survey_questions_survey_order (survey_id, display_order),
    CONSTRAINT fk_survey_questions_survey FOREIGN KEY (survey_id) REFERENCES surveys(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
