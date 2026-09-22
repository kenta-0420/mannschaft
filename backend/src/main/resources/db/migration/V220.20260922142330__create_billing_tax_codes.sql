-- =====================================================================
-- 価格改定戦役（price-revisions）1本目: 税コードマスタ billing_tax_codes
-- =====================================================================
-- 正本: .claude/campaigns/price-rev-plan-v3.md 決定5・決定6・AC-1〜AC-3
--
-- 税コードは同一 code で複数の有効期間（税率改定履歴）を持てる。uk_btc_code_from で
-- (code, valid_from) の重複登録を防ぎ、有効期間の重なり判定はアプリ層（BillingTaxCodeService）が
-- 専用ロック行 __TAX_CODE_LOCK__ を FOR UPDATE してから直列に行う。
-- =====================================================================

CREATE TABLE billing_tax_codes (
    id                  BINARY(16)      NOT NULL,
    code                VARCHAR(64)     NOT NULL,
    display_name        VARCHAR(64)     NOT NULL,
    rate_basis_points   INT             NOT NULL,
    stripe_tax_code     VARCHAR(64)     NULL,
    valid_from          DATETIME(6)     NOT NULL,
    valid_until         DATETIME(6)     NULL,
    enabled             TINYINT(1)      NOT NULL DEFAULT 1,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at          DATETIME(6)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_btc_code_from (code, valid_from),
    KEY ix_btc_code_valid_from (code, valid_from),
    CONSTRAINT chk_btc_rate CHECK (rate_basis_points BETWEEN 0 AND 10000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 初期 seed: 日本の標準税率10% / 軽減税率8%（決定6・AC-2）
INSERT INTO billing_tax_codes
    (id, code, display_name, rate_basis_points, stripe_tax_code, valid_from, valid_until, enabled)
VALUES
    (UNHEX(REPLACE(UUID(), '-', '')), 'JP_STANDARD_10', '標準税率10%', 1000, 'txcd_99999999', '1970-01-01 00:00:00.000000', NULL, TRUE),
    (UNHEX(REPLACE(UUID(), '-', '')), 'JP_REDUCED_8', '軽減税率8%', 800, 'txcd_99999999', '1970-01-01 00:00:00.000000', NULL, TRUE);

-- 税コード専用ロック行（AC-3）。enabled=false のため通常の一覧・解決 API からは常に除外される。
INSERT INTO billing_tax_codes
    (id, code, display_name, rate_basis_points, stripe_tax_code, valid_from, valid_until, enabled)
VALUES
    (UNHEX(REPLACE(UUID(), '-', '')), '__TAX_CODE_LOCK__', 'lock row', 0, NULL, '1970-01-01 00:00:00.000000', NULL, FALSE);
