-- F02.1 QR会員証: セルフチェックイン拠点テーブル
CREATE TABLE checkin_locations (
    id                          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    scope_type                  VARCHAR(20)      NOT NULL COMMENT 'スコープ種別（TEAM / ORGANIZATION）',
    scope_id                    BIGINT UNSIGNED  NOT NULL COMMENT 'FK → teams.id or organizations.id',
    name                        VARCHAR(100)     NOT NULL COMMENT '拠点名',
    location_code               CHAR(36)         NOT NULL COMMENT '拠点コード（UUID v4）',
    location_secret             VARCHAR(64)      NOT NULL COMMENT 'QR署名用シークレット（HMAC-SHA256）',
    is_active                   BOOLEAN          NOT NULL DEFAULT TRUE COMMENT '有効フラグ',
    auto_complete_reservation   BOOLEAN          NOT NULL DEFAULT TRUE COMMENT 'セルフチェックイン時に当日予約を自動COMPLETEDにするか',
    created_by                  BIGINT UNSIGNED  NULL     COMMENT 'FK → users',
    created_at                  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at                  DATETIME         NULL     COMMENT '論理削除',
    PRIMARY KEY (id),
    UNIQUE KEY uq_cl_location_code (location_code),
    INDEX idx_cl_scope (scope_type, scope_id, is_active),
    CONSTRAINT fk_cl_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='セルフチェックイン拠点';
