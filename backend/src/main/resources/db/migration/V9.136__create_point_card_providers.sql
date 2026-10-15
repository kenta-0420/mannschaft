-- F18 個人ポイントカードウォレット — プロバイダーマスタ
-- 案 B 改修後: ロゴ・色補強用の縮小マスタ（Phase 1 は 10 社）+ Phase 2 で自店発行も受け入れる
-- 設計書: docs/features/F18_point_card_wallet.md §5.1
CREATE TABLE point_card_providers (
    id                       CHAR(36)        NOT NULL,
    code                     VARCHAR(50)     NOT NULL,
    display_name             VARCHAR(100)    NOT NULL,
    category                 VARCHAR(20)     NOT NULL,
    type                     VARCHAR(30)     NOT NULL DEFAULT 'EXTERNAL',
    organization_id          BIGINT UNSIGNED NULL,
    logo_url                 VARCHAR(500)    NULL,
    brand_color              CHAR(7)         NULL,
    default_barcode_format   VARCHAR(20)     NULL,
    card_number_regex        VARCHAR(200)    NULL,
    card_number_length_hint  VARCHAR(50)     NULL,
    is_active                TINYINT(1)      NOT NULL DEFAULT 1,
    legal_notice             TEXT            NULL,
    created_at               DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_pcp_code (code),
    INDEX idx_pcp_category_active (category, is_active),
    INDEX idx_pcp_type_org (type, organization_id),
    -- type と organization_id の整合性: EXTERNAL は organization_id=NULL、SELF_ISSUED_* は organization_id 必須
    CONSTRAINT chk_pcp_type_org_consistency CHECK (
        (type = 'EXTERNAL' AND organization_id IS NULL)
        OR (type IN ('SELF_ISSUED_STAMP', 'SELF_ISSUED_BALANCE') AND organization_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
