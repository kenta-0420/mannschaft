-- 問診票テンプレート定義テーブル
CREATE TABLE chart_intake_form_templates (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id BIGINT UNSIGNED NOT NULL,
    form_type VARCHAR(20) NOT NULL,
    template_name VARCHAR(100) NOT NULL,
    template_json JSON NOT NULL,
    is_default TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_cift_team FOREIGN KEY (team_id) REFERENCES teams(id)
);
CREATE INDEX idx_cift_team ON chart_intake_form_templates(team_id);
CREATE INDEX idx_cift_team_type ON chart_intake_form_templates(team_id, form_type);
