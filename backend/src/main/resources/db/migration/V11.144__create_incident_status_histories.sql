-- インシデントステータス履歴テーブル
CREATE TABLE incident_status_histories (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    incident_id BIGINT       NOT NULL,
    from_status VARCHAR(30)  NULL     COMMENT '初回REPORTEDはNULL',
    to_status   VARCHAR(30)  NOT NULL,
    changed_by  BIGINT       NOT NULL,
    comment     VARCHAR(500) NULL,
    created_at  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ish_incident    FOREIGN KEY (incident_id) REFERENCES incidents (id),
    CONSTRAINT fk_ish_changed_by  FOREIGN KEY (changed_by)  REFERENCES users (id),
    INDEX idx_ish_incident (incident_id, created_at)
);
