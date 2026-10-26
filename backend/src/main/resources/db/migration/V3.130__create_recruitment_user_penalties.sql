-- F03.11 Phase 5b: ユーザーごとのペナルティ状態テーブル
-- アクティブペナルティの重複はサービス層の PESSIMISTIC_WRITE で防止する。
CREATE TABLE recruitment_user_penalties (
    id                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id                  BIGINT UNSIGNED NOT NULL COMMENT '対象ユーザーID',
    scope_type               ENUM('TEAM', 'ORGANIZATION') NOT NULL,
    scope_id                 BIGINT UNSIGNED NOT NULL,
    penalty_type             ENUM('NO_SHOW') NOT NULL DEFAULT 'NO_SHOW',
    triggered_by_setting_id  BIGINT UNSIGNED NOT NULL COMMENT 'ペナルティ発動に使用した設定ID',
    triggered_no_show_count  INT         NOT NULL COMMENT '発動時のNO_SHOW件数',
    started_at               DATETIME(6) NOT NULL COMMENT 'ペナルティ開始日時',
    expires_at               DATETIME(6) NOT NULL COMMENT 'ペナルティ失効予定日時',
    lifted_at                DATETIME(6) NULL     COMMENT '実際の解除日時（失効ormanual）',
    lifted_by                BIGINT UNSIGNED NULL     COMMENT '手動解除実施者ユーザーID',
    lift_reason              ENUM('AUTO_EXPIRED', 'ADMIN_MANUAL', 'DISPUTE_REVOKED') NULL,
    created_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_rup_user    FOREIGN KEY (user_id)                 REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_rup_setting FOREIGN KEY (triggered_by_setting_id) REFERENCES recruitment_penalty_settings (id),
    INDEX idx_rup_user_scope  (user_id, scope_type, scope_id),
    INDEX idx_rup_expires     (expires_at),
    INDEX idx_rup_lifted      (lifted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F03.11 Phase5b: ユーザーペナルティ状態';
