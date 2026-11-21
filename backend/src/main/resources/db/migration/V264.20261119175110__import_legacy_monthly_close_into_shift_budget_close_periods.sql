-- CMP-260909-2152 α: 旧締め（budget_transactions の SHIFT_BUDGET_MONTHLY 生存行。V209 が論理削除した行は除く）を
-- shift_budget_close_periods（暦月の期間行）・shift_budget_monthly_close_events（CLOSED／UNKNOWN／LEGACY_IMPORT）・
-- budget_shift_close_postings（POSTED）へ取り込む。DML のみ。原仕訳は変更しない。close_event_id の印は付けない。
--
-- 防御: 事前検査（FlywayRepairConfig）を通らない直実行でも、異常な旧仕訳（月末日以外・同割当同月2件以上・割当なし）では
-- 一時表の CHECK 違反で失敗する（推測で取り込まない）。
CREATE TEMPORARY TABLE tmp_sbcp_import_guard (
    ok TINYINT NOT NULL,
    CONSTRAINT chk_tmp_sbcp_import_guard CHECK (ok = 1)
);

INSERT INTO tmp_sbcp_import_guard (ok)
SELECT 0
FROM budget_transactions t
LEFT JOIN shift_budget_allocations a ON a.id = t.source_id
WHERE t.source_type = 'SHIFT_BUDGET_MONTHLY' AND t.deleted_at IS NULL
  AND (a.id IS NULL OR t.transaction_date <> LAST_DAY(t.transaction_date));

INSERT INTO tmp_sbcp_import_guard (ok)
SELECT 0
FROM (
    SELECT t.source_id, DATE_FORMAT(t.transaction_date, '%Y-%m') AS ym
    FROM budget_transactions t
    WHERE t.source_type = 'SHIFT_BUDGET_MONTHLY' AND t.deleted_at IS NULL
    GROUP BY t.source_id, DATE_FORMAT(t.transaction_date, '%Y-%m')
    HAVING COUNT(*) > 1
) dup;

INSERT INTO shift_budget_close_periods (id, organization_id, period_key, period_start, period_end, closing_day, created_at)
SELECT UUID_TO_BIN(UUID(), 1), x.organization_id, x.ym, STR_TO_DATE(CONCAT(x.ym, '-01'), '%Y-%m-%d'),
       LAST_DAY(STR_TO_DATE(CONCAT(x.ym, '-01'), '%Y-%m-%d')), 0, UTC_TIMESTAMP(6)
FROM (
    SELECT DISTINCT a.organization_id, DATE_FORMAT(t.transaction_date, '%Y-%m') AS ym
    FROM budget_transactions t
    JOIN shift_budget_allocations a ON a.id = t.source_id
    WHERE t.source_type = 'SHIFT_BUDGET_MONTHLY' AND t.deleted_at IS NULL
) x
WHERE NOT EXISTS (
    SELECT 1 FROM shift_budget_close_periods p WHERE p.organization_id = x.organization_id AND p.period_key = x.ym
);

INSERT INTO shift_budget_monthly_close_events (id, allocation_id, target_month, close_period_id, journal_date, state, origin,
                                               line_count, planned_total, actor_kind, actor_user_id, prepared_at, closed_at,
                                               journal_transaction_id)
SELECT UUID_TO_BIN(UUID(), 1), a.id, DATE_FORMAT(t.transaction_date, '%Y-%m'), p.id, t.transaction_date, 'CLOSED',
       'LEGACY_IMPORT', 0, t.amount, 'UNKNOWN', NULL, t.created_at, t.created_at, t.id
FROM budget_transactions t
JOIN shift_budget_allocations a ON a.id = t.source_id
JOIN shift_budget_close_periods p ON p.organization_id = a.organization_id
                                 AND p.period_key = DATE_FORMAT(t.transaction_date, '%Y-%m')
WHERE t.source_type = 'SHIFT_BUDGET_MONTHLY' AND t.deleted_at IS NULL;

INSERT INTO budget_shift_close_postings (id, close_event_id, allocation_id, fiscal_year_id, outcome, journal_transaction_id,
                                         reason, decided_at)
SELECT UUID_TO_BIN(UUID(), 1), e.id, e.allocation_id, t.fiscal_year_id, 'POSTED', t.id, NULL, t.created_at
FROM shift_budget_monthly_close_events e
JOIN budget_transactions t ON t.id = e.journal_transaction_id
WHERE e.origin = 'LEGACY_IMPORT';

DROP TEMPORARY TABLE tmp_sbcp_import_guard;
