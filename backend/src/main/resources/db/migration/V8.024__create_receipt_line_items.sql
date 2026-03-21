-- F08.4: 領収書発行 — 領収書明細行テーブル（複数税率対応）
CREATE TABLE receipt_line_items (
    id              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    receipt_id      BIGINT UNSIGNED  NOT NULL COMMENT 'FK → receipts',
    description     VARCHAR(200)     NOT NULL COMMENT '明細の説明',
    amount          DECIMAL(10,0)    NOT NULL COMMENT '税込金額',
    tax_rate        DECIMAL(4,2)     NOT NULL COMMENT '適用税率（%）',
    tax_amount      DECIMAL(10,0)    NOT NULL COMMENT '税額',
    amount_excl_tax DECIMAL(10,0)    NOT NULL COMMENT '税抜金額',
    sort_order      INT              NOT NULL DEFAULT 0 COMMENT '表示順（昇順）',
    PRIMARY KEY (id),
    INDEX idx_rli_receipt (receipt_id, sort_order),
    CONSTRAINT fk_rli_receipt FOREIGN KEY (receipt_id) REFERENCES receipts (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='領収書明細行';
