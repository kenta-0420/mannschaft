-- F08.4: 領収書発行 — 発行待ちキューテーブル
CREATE TABLE receipt_queue (
    id                    BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    scope_type            VARCHAR(20)      NOT NULL COMMENT 'スコープ（TEAM / ORGANIZATION）',
    scope_id              BIGINT UNSIGNED  NOT NULL COMMENT 'スコープ ID',
    member_payment_id     BIGINT UNSIGNED  NOT NULL COMMENT 'FK → member_payments。決済完了した支払い',
    recipient_user_id     BIGINT UNSIGNED  NOT NULL COMMENT 'FK → users。受領者',
    suggested_description VARCHAR(500)     NULL     COMMENT '自動生成された但し書き候補',
    suggested_amount      DECIMAL(10,0)    NOT NULL COMMENT '支払い額',
    preset_id             BIGINT UNSIGNED  NULL     COMMENT 'FK → receipt_presets。マッチしたプリセット',
    status                VARCHAR(20)      NOT NULL DEFAULT 'PENDING' COMMENT 'ステータス（PENDING / APPROVED / SKIPPED）',
    processed_receipt_id  BIGINT UNSIGNED  NULL     COMMENT 'FK → receipts。承認後に発行された領収書',
    created_at            DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_rq_scope_status (scope_type, scope_id, status, created_at DESC),
    UNIQUE KEY uq_rq_payment (member_payment_id),
    CONSTRAINT fk_rq_recipient_user FOREIGN KEY (recipient_user_id) REFERENCES users (id),
    CONSTRAINT fk_rq_preset FOREIGN KEY (preset_id) REFERENCES receipt_presets (id),
    CONSTRAINT fk_rq_processed_receipt FOREIGN KEY (processed_receipt_id) REFERENCES receipts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='領収書発行待ちキュー';
