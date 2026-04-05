-- カルテ本体テーブル
CREATE TABLE chart_records (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id BIGINT UNSIGNED NOT NULL,
    customer_user_id BIGINT UNSIGNED NOT NULL,
    staff_user_id BIGINT UNSIGNED NULL,
    visit_date DATE NOT NULL,
    chief_complaint TEXT NULL,
    treatment_note TEXT NULL,
    next_recommendation TEXT NULL,
    next_visit_recommended_date DATE NULL,
    allergy_info TEXT NULL,
    is_shared_to_customer TINYINT(1) NOT NULL DEFAULT 0,
    is_pinned TINYINT(1) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_cr_team FOREIGN KEY (team_id) REFERENCES teams(id),
    CONSTRAINT fk_cr_customer FOREIGN KEY (customer_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_cr_staff FOREIGN KEY (staff_user_id) REFERENCES users(id) ON DELETE SET NULL
);
CREATE INDEX idx_cr_team_id ON chart_records(team_id);
CREATE INDEX idx_cr_customer ON chart_records(customer_user_id);
CREATE INDEX idx_cr_team_customer ON chart_records(team_id, customer_user_id);
CREATE INDEX idx_cr_visit_date ON chart_records(team_id, visit_date DESC);
CREATE INDEX idx_cr_staff ON chart_records(staff_user_id);
