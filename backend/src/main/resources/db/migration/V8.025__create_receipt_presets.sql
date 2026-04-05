-- F08.4: 領収書発行 — 発行プリセットテーブル
CREATE TABLE receipt_presets (
    id                   BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    scope_type           VARCHAR(20)      NOT NULL COMMENT 'スコープ（TEAM / ORGANIZATION）',
    scope_id             BIGINT UNSIGNED  NOT NULL COMMENT 'スコープ ID',
    name                 VARCHAR(100)     NOT NULL COMMENT 'プリセット名',
    description          VARCHAR(500)     NOT NULL COMMENT '但し書き',
    amount               DECIMAL(10,0)    NOT NULL COMMENT '税込金額',
    tax_rate             DECIMAL(4,2)     NOT NULL DEFAULT 10.00 COMMENT '適用税率（%）',
    line_items_json      JSON             NULL     COMMENT '複数税率の明細行定義（JSON 配列）',
    payment_method_label VARCHAR(50)      NULL     COMMENT '支払い方法の表示名',
    seal_stamp           BOOLEAN          NOT NULL DEFAULT TRUE COMMENT '電子印鑑を押印するか',
    created_by           BIGINT UNSIGNED  NOT NULL COMMENT 'FK → users。作成した ADMIN',
    created_at           DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at           DATETIME         NULL     COMMENT '論理削除日時',
    PRIMARY KEY (id),
    INDEX idx_rp_scope (scope_type, scope_id),
    CONSTRAINT fk_rp_created_by FOREIGN KEY (created_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='領収書発行プリセット';
