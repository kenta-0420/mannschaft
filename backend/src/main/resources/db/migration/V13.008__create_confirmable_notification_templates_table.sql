-- F04.9: 確認通知テンプレートテーブル（論理削除あり）
CREATE TABLE confirmable_notification_templates (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type       ENUM('TEAM', 'ORGANIZATION') NOT NULL,
    scope_id         BIGINT UNSIGNED NOT NULL,
    name             VARCHAR(100) NOT NULL COMMENT '管理用テンプレート名',
    title            VARCHAR(200) NOT NULL,
    body             TEXT NULL,
    default_priority ENUM('NORMAL', 'HIGH', 'URGENT') NOT NULL DEFAULT 'NORMAL',
    created_by       BIGINT UNSIGNED NULL,
    deleted_at       DATETIME NULL,
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_cnt_scope (scope_type, scope_id, deleted_at),
    CONSTRAINT fk_cnt_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='確認通知テンプレート';
