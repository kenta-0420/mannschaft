-- CMP-260909-2152 α: 締めの投稿判定表（budget ドメイン。追記専用）。1イベントにつき投稿／中止をちょうど1回だけ決める正本。
-- close_event_id は shiftbudget ドメインの ID のため FK を張らない（クロスドメイン FK 禁止）。
CREATE TABLE budget_shift_close_postings (
    id BINARY(16) NOT NULL,
    close_event_id BINARY(16) NOT NULL,
    allocation_id BIGINT UNSIGNED NOT NULL,
    fiscal_year_id BIGINT UNSIGNED NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    journal_transaction_id BIGINT UNSIGNED NULL,
    reason VARCHAR(40) NULL,
    decided_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_bscp_event (close_event_id),
    KEY idx_bscp_allocation (allocation_id),
    CONSTRAINT chk_bscp_outcome CHECK (outcome IN ('POSTED', 'ABORTED')),
    CONSTRAINT chk_bscp_posted CHECK ((outcome = 'POSTED') = (journal_transaction_id IS NOT NULL)),
    CONSTRAINT fk_bscp_journal FOREIGN KEY (journal_transaction_id) REFERENCES budget_transactions (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
