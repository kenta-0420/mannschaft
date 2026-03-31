-- インシデント添付ファイルテーブル
CREATE TABLE incident_attachments (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    incident_id BIGINT UNSIGNED NOT NULL,
    file_key    VARCHAR(500)  NOT NULL,
    mime_type   VARCHAR(50)   NOT NULL COMMENT 'JPEG/PNG/WebPのみ',
    created_by  BIGINT UNSIGNED NOT NULL,
    created_at  DATETIME      NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ia_incident   FOREIGN KEY (incident_id) REFERENCES incidents (id),
    CONSTRAINT fk_ia_created_by FOREIGN KEY (created_by)  REFERENCES users (id),
    INDEX idx_ia_incident (incident_id)
);
