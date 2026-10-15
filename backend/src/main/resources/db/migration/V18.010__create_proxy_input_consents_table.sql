-- F14.1 代理入力・非デジタル住民対応: 代理入力同意書テーブル
CREATE TABLE IF NOT EXISTS proxy_input_consents (
    id                          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    subject_user_id             BIGINT UNSIGNED NOT NULL COMMENT '代理される本人 FK→users.id',
    proxy_user_id               BIGINT UNSIGNED NOT NULL COMMENT '代理者 FK→users.id',
    organization_id             BIGINT UNSIGNED NOT NULL COMMENT '同意の有効範囲（組合） FK→organizations.id',
    consent_method              VARCHAR(32)     NOT NULL COMMENT 'PAPER_SIGNED / WITNESSED_ORAL / DIGITAL_SIGNATURE / GUARDIAN_BY_COURT',
    scanned_document_s3_key     VARCHAR(512)    NULL     COMMENT '同意書スキャンPDFのS3オブジェクトキー（presigned URLは閲覧時都度発行）',
    guardian_certificate_s3_key VARCHAR(512)    NULL     COMMENT 'GUARDIAN_BY_COURT時の後見登記事項証明書S3キー',
    witness_user_id             BIGINT UNSIGNED NULL     COMMENT 'WITNESSED_ORAL時の立会人（ADMIN必須）FK→users.id',
    effective_from              DATE            NOT NULL COMMENT '有効開始日',
    effective_until             DATE            NOT NULL COMMENT '有効期限（最長1年・更新要）',
    revoked_at                  DATETIME        NULL     COMMENT '撤回日時',
    revoke_method               VARCHAR(32)     NULL     COMMENT 'API_BY_SUBJECT / PAPER_BY_SUBJECT / AUTO_BY_LIFE_EVENT / AUTO_BY_TENURE_END',
    revoke_witnessed_by_user_id BIGINT UNSIGNED NULL     COMMENT '紙撤回届の立会ADMIN FK→users.id',
    revoke_reason               VARCHAR(255)    NULL,
    approved_by_user_id         BIGINT UNSIGNED NULL     COMMENT '承認した管理者（proxy_user_idとは別のADMINまたはSYSTEM_ADMIN）FK→users.id',
    approved_at                 DATETIME        NULL,
    created_at                  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at                  DATETIME        NULL,

    PRIMARY KEY (id),
    INDEX idx_pic_subject (subject_user_id, effective_until),
    INDEX idx_pic_proxy   (proxy_user_id, effective_until),
    INDEX idx_pic_org     (organization_id, effective_until),
    INDEX idx_pic_active  (subject_user_id, proxy_user_id, organization_id, effective_from),

    CONSTRAINT fk_pic_subject      FOREIGN KEY (subject_user_id)             REFERENCES users(id),
    CONSTRAINT fk_pic_proxy        FOREIGN KEY (proxy_user_id)               REFERENCES users(id),
    CONSTRAINT fk_pic_org          FOREIGN KEY (organization_id)             REFERENCES organizations(id),
    CONSTRAINT fk_pic_witness      FOREIGN KEY (witness_user_id)             REFERENCES users(id),
    CONSTRAINT fk_pic_approved_by  FOREIGN KEY (approved_by_user_id)         REFERENCES users(id),
    CONSTRAINT fk_pic_revoke_wit   FOREIGN KEY (revoke_witnessed_by_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='代理入力の本人同意書（F14.1）';
