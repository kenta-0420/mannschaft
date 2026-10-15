-- カルテ作成テンプレートテーブル
CREATE TABLE chart_record_templates (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id BIGINT UNSIGNED NOT NULL,
    template_name VARCHAR(100) NOT NULL,
    chief_complaint TEXT NULL,
    treatment_note TEXT NULL,
    allergy_info TEXT NULL,
    default_custom_fields JSON NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_crt_team FOREIGN KEY (team_id) REFERENCES teams(id)
);
CREATE INDEX idx_crt_team_sort ON chart_record_templates(team_id, sort_order);
