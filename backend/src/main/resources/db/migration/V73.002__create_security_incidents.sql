-- V73.002: GDPR Article 33 対応セキュリティインシデント管理テーブル
CREATE TABLE security_incidents (
    id               BINARY(16)   NOT NULL COMMENT 'UUIDv7',
    incident_type    VARCHAR(50)  NOT NULL,
    severity         VARCHAR(20)  NOT NULL,
    detected_at      DATETIME(6)  NOT NULL,
    records_affected INT,
    description      TEXT,
    status           VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    notified_dpa_at  DATETIME(6),
    resolved_at      DATETIME(6),
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_security_incidents_status (status),
    INDEX idx_security_incidents_detected_at (detected_at),
    INDEX idx_security_incidents_severity (severity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GDPR Article 33 セキュリティインシデント管理';
