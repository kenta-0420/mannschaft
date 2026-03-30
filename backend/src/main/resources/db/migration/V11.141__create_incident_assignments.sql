-- インシデント担当者テーブル
CREATE TABLE incident_assignments (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    incident_id     BIGINT       NOT NULL,
    assignee_type   VARCHAR(20)  NOT NULL COMMENT 'USER/EXTERNAL',
    user_id         BIGINT       NULL     COMMENT 'assignee_type=USERの場合',
    external_name   VARCHAR(100) NULL     COMMENT 'assignee_type=EXTERNALの場合',
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ias_incident FOREIGN KEY (incident_id) REFERENCES incidents (id),
    CONSTRAINT fk_ias_user     FOREIGN KEY (user_id)     REFERENCES users (id),
    INDEX idx_ias_incident (incident_id),
    INDEX idx_ias_user     (user_id)
);
