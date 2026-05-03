-- F14.1 代理入力・非デジタル住民対応: 代理入力実行ログテーブル（集計分離専用・追記のみ）
CREATE TABLE proxy_input_records (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    proxy_input_consent_id  BIGINT UNSIGNED NOT NULL COMMENT '根拠となる同意書 FK→proxy_input_consents.id',
    subject_user_id         BIGINT UNSIGNED NOT NULL COMMENT '本人（集計用冗長保持）FK→users.id',
    proxy_user_id           BIGINT UNSIGNED NOT NULL COMMENT '代理者 FK→users.id',
    feature_scope           VARCHAR(64)     NOT NULL COMMENT '操作対象機能（FeatureScopeと連動）',
    target_entity_type      VARCHAR(64)     NOT NULL COMMENT 'SURVEY_RESPONSE / SCHEDULE_ATTENDANCE_RESPONSE 等',
    target_entity_id        BIGINT UNSIGNED NOT NULL COMMENT '操作対象レコードID',
    input_source            VARCHAR(32)     NOT NULL COMMENT 'PAPER_FORM / PHONE_INTERVIEW / IN_PERSON',
    original_storage_location VARCHAR(255)  NOT NULL COMMENT '紙原本の保管場所（紛争時証跡・必須）',
    audit_log_id            BIGINT UNSIGNED NULL     COMMENT '同期作成されたaudit_logs.idへの参照',
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_pir_idempotent (proxy_input_consent_id, target_entity_type, target_entity_id),
    INDEX idx_pir_subject_feature (subject_user_id, feature_scope, created_at),
    INDEX idx_pir_proxy_created   (proxy_user_id, created_at),
    INDEX idx_pir_consent         (proxy_input_consent_id),

    CONSTRAINT fk_pir_consent FOREIGN KEY (proxy_input_consent_id) REFERENCES proxy_input_consents(id),
    CONSTRAINT fk_pir_subject FOREIGN KEY (subject_user_id)         REFERENCES users(id),
    CONSTRAINT fk_pir_proxy   FOREIGN KEY (proxy_user_id)           REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='代理入力の実行ログ（集計分離専用・最小列構成・追記のみ）（F14.1）';
