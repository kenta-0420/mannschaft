-- F06.4: 活動レベルカスタムフィールド値テーブル
CREATE TABLE activity_custom_values (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_result_id BIGINT UNSIGNED NOT NULL,
    custom_field_id    BIGINT UNSIGNED NOT NULL,
    scope              VARCHAR(15) NOT NULL DEFAULT 'ACTIVITY',
    value              TEXT NULL,
    created_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_acv_result_field (activity_result_id, custom_field_id),
    INDEX idx_acv_field (custom_field_id),
    CONSTRAINT chk_acv_scope CHECK (scope = 'ACTIVITY'),
    CONSTRAINT fk_acv_result FOREIGN KEY (activity_result_id) REFERENCES activity_results (id) ON DELETE CASCADE,
    CONSTRAINT fk_acv_field_scope FOREIGN KEY (custom_field_id, scope) REFERENCES activity_custom_fields (id, scope) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- F06.4: 参加者レベルカスタムフィールド値テーブル
CREATE TABLE activity_participant_values (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    participant_id  BIGINT UNSIGNED NOT NULL,
    custom_field_id BIGINT UNSIGNED NOT NULL,
    scope           VARCHAR(15) NOT NULL DEFAULT 'PARTICIPANT',
    value           TEXT NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_apv_participant_field (participant_id, custom_field_id),
    INDEX idx_apv_field (custom_field_id),
    INDEX idx_apv_field_value (custom_field_id, value(100)),
    CONSTRAINT chk_apv_scope CHECK (scope = 'PARTICIPANT'),
    CONSTRAINT fk_apv_participant FOREIGN KEY (participant_id) REFERENCES activity_participants (id) ON DELETE CASCADE,
    CONSTRAINT fk_apv_field_scope FOREIGN KEY (custom_field_id, scope) REFERENCES activity_custom_fields (id, scope) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
