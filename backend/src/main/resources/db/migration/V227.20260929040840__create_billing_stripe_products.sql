-- =====================================================================
-- 価格改定戦役（price-revisions）3本目: Stripe Product 永続マッピング billing_stripe_products
-- =====================================================================
-- 正本: .claude/campaigns/price-rev-plan-v3.md 決定9改訂・決定5
--
-- Product 解決キーは (product_kind, product_key, stripe_tax_code) の組。stripe_tax_code は
-- NULL を取り得るため、素の UNIQUE KEY では NULL 同士が別扱いになり重複作成を防げない。
-- 生成列 stripe_tax_code_norm で NULL を空文字に正規化してから UNIQUE を張る。
-- =====================================================================

CREATE TABLE billing_stripe_products (
    id                      BINARY(16)      NOT NULL,
    product_kind            VARCHAR(16)     NOT NULL,
    product_key             VARCHAR(64)     NOT NULL,
    stripe_tax_code         VARCHAR(64)     NULL,
    stripe_tax_code_norm    VARCHAR(64)     GENERATED ALWAYS AS (COALESCE(stripe_tax_code, '')) STORED,
    stripe_product_id       VARCHAR(255)    NOT NULL,
    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_bsp_kind_key_tax (product_kind, product_key, stripe_tax_code_norm)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
