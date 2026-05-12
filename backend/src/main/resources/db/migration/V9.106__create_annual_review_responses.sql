-- F09.16 S1-B: residence-status ドメイン
-- annual_review_responses（各居住者回答メタ）
-- annual_reviews とは同ドメイン UUIDv7 FK CASCADE（CLAUDE.md DB設計原則 2 準拠）
-- dwelling_unit_id / resident_registry_id / respondent_user_id はクロスドメインのため INDEX のみ
CREATE TABLE annual_review_responses (
    id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    annual_review_id BINARY(16) NOT NULL,
    dwelling_unit_id BIGINT UNSIGNED NOT NULL,
    resident_registry_id BIGINT UNSIGNED NOT NULL,
    respondent_user_id BIGINT UNSIGNED NOT NULL,
    residence_state VARCHAR(30) NOT NULL DEFAULT 'UNRESPONDED',
    contact_phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
    contact_email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    emergency_contact_verified BOOLEAN NOT NULL DEFAULT FALSE,
    responded_at DATETIME(6) NULL,
    note TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_arr_review_resident (annual_review_id, resident_registry_id, deleted_at),
    INDEX idx_arr_review_state (annual_review_id, residence_state),
    INDEX idx_arr_resident (resident_registry_id, responded_at DESC),
    INDEX idx_arr_org (organization_id, deleted_at),
    INDEX idx_arr_dwelling (dwelling_unit_id),
    INDEX idx_arr_respondent (respondent_user_id),
    CONSTRAINT fk_arr_annual_review FOREIGN KEY (annual_review_id)
        REFERENCES annual_reviews (id) ON DELETE CASCADE,
    CONSTRAINT chk_arr_residence_state CHECK (residence_state IN (
        'UNRESPONDED','OWNER_RESIDING','RENTED_OUT','LONG_ABSENCE','VACANT','OTHER'
    ))
);
