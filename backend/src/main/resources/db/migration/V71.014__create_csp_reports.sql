-- CSP 違反レポート受信テーブル。
-- ブラウザから POST /api/v1/security/csp-reports で送信されたレポートを蓄積する。
-- report_hash による重複集約で同一違反パターンを 1 レコードに束ねる。
CREATE TABLE csp_reports (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    document_uri         VARCHAR(1000),
    blocked_uri          VARCHAR(1000),
    violated_directive   VARCHAR(200),
    effective_directive  VARCHAR(200),
    original_policy      TEXT,
    disposition          VARCHAR(20),
    script_sample        VARCHAR(500),
    status_code          INT,
    -- SHA-256(violated_directive + "|" + document_uri + "|" + blocked_uri)
    report_hash          VARCHAR(64)   NOT NULL,
    occurrence_count     INT           NOT NULL DEFAULT 1,
    ip_address           VARCHAR(45),
    user_agent           VARCHAR(500),
    first_seen_at        DATETIME(6)   NOT NULL,
    last_seen_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_csp_reports_hash      (report_hash),
    INDEX idx_csp_reports_directive (violated_directive),
    INDEX idx_csp_reports_last_seen (last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
