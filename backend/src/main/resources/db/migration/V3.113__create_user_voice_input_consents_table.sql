-- 音声入力同意履歴（GDPR 同意証跡）
CREATE TABLE user_voice_input_consents (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT UNSIGNED NOT NULL COMMENT 'FK -> users.id (ON DELETE CASCADE)',
    version      INT          NOT NULL COMMENT '同意した音声ポリシーのバージョン番号',
    consented_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    revoked_at   DATETIME(6)  NULL     COMMENT 'NULL = 有効、非NULL = 取消済み',
    user_agent   VARCHAR(512) NULL,
    ip_address   VARCHAR(45)  NULL     COMMENT 'IPv4 or IPv6',
    PRIMARY KEY (id),
    CONSTRAINT fk_uvic_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='音声入力同意履歴';

CREATE INDEX idx_uvic_user_version ON user_voice_input_consents (user_id, version, revoked_at);
