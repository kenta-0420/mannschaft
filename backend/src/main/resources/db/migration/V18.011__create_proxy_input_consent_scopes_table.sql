-- F14.1 代理入力・非デジタル住民対応: 同意書ごとの許可機能スコープテーブル
CREATE TABLE IF NOT EXISTS proxy_input_consent_scopes (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    proxy_input_consent_id  BIGINT UNSIGNED NOT NULL COMMENT 'FK→proxy_input_consents.id',
    feature_scope           VARCHAR(64)     NOT NULL COMMENT 'SURVEY / SCHEDULE_ATTENDANCE / SHIFT_REQUEST / ANNOUNCEMENT_READ / PARKING_APPLICATION / CIRCULAR / SUPPORTER_VIEW',
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_pics_consent_scope (proxy_input_consent_id, feature_scope),
    INDEX idx_pics_scope (feature_scope),

    CONSTRAINT fk_pics_consent FOREIGN KEY (proxy_input_consent_id)
        REFERENCES proxy_input_consents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='同意書ごとの許可機能スコープ（F14.1）';
