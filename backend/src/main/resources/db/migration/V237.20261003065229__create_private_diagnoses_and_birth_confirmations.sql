-- 本人診断・プロフィール確認の永続化。auth既存暗号化欄へ出生情報を補完し、他ドメインへ原情報を複製しない。
ALTER TABLE users ADD COLUMN birth_profile_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE diagnosis_sessions (
    id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(30) NOT NULL,
    questionnaire_version VARCHAR(80) NOT NULL,
    scoring_version VARCHAR(80) NOT NULL,
    questions_snapshot LONGTEXT NOT NULL,
    answers_snapshot LONGTEXT NOT NULL,
    answer_revision BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    result_id BINARY(16) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_diagnosis_sessions_user_id (user_id),
    CONSTRAINT chk_diagnosis_sessions_revision CHECK (answer_revision >= 0),
    CONSTRAINT chk_diagnosis_sessions_status CHECK (status IN ('STARTED','TIE_BREAK_REQUIRED','COMPLETED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本人の非公開診断セッション';

CREATE TABLE diagnosis_results (
    id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    method VARCHAR(30) NOT NULL,
    source_profile_revision BIGINT NULL,
    summary_snapshot LONGTEXT NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_diagnosis_results_user_id_completed_at_id (user_id, completed_at DESC, id DESC),
    INDEX idx_diagnosis_results_user_id_method_completed_at_id (user_id, method, completed_at DESC, id DESC),
    CONSTRAINT chk_diagnosis_results_method CHECK (method IN ('DIAGNOSIS','BIRTH_STYLE')),
    CONSTRAINT chk_diagnosis_results_source_profile_revision CHECK (
        (method = 'DIAGNOSIS' AND source_profile_revision IS NULL)
        OR (method = 'BIRTH_STYLE' AND source_profile_revision IS NOT NULL AND source_profile_revision >= 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='出生原情報と回答を含まない不変本人結果';

CREATE TABLE diagnosis_commands (
    id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    command_id BINARY(16) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_snapshot LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_diagnosis_commands_user_id_command_id UNIQUE (user_id, command_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本人診断の成功命令と非公開再送応答';

CREATE TABLE birth_profile_confirmations (
    id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    profile_revision BIGINT NOT NULL,
    withdrawal_attempt_id BINARY(16) NULL,
    fingerprint VARCHAR(64) NOT NULL,
    signature VARCHAR(64) NOT NULL,
    key_id VARCHAR(64) NOT NULL,
    purpose VARCHAR(80) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_birth_profile_confirmations_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='PIIを複製しない本人確認の署名証跡';

CREATE TABLE birth_profile_commands (
    id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    command_id BINARY(16) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_snapshot LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_birth_profile_commands_user_id_command_id UNIQUE (user_id, command_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本人出生情報操作のPIIを含まない再送応答';
