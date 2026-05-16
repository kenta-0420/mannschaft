-- F18 Phase 3: 残高型カードの残高変動履歴
-- 設計書 docs/features/F18_point_card_wallet.md §12.1 / §16
--
-- 設計方針:
--   * operation_type: CHARGE / SPENT / REFUND（設計書の表記に準拠）
--   * delta: 1 操作あたり ±1,000,000 円まで（DB CHECK + Service 二段ガード）
--   * balance_after: 0 〜 10,000,000 円（DB CHECK + Service 二段ガード）
--   * refund_of_event_id: 返金時に元 event を自己参照（元 event 削除時は SET NULL）
--
-- FK 方針（CLAUDE.md 原則 1 / 原則 2）:
--   * card_id → user_point_cards(id) CASCADE  ※ Phase 2 stamp_events と同じ、同一機能ドメイン
--   * provider_id → point_card_providers(id) RESTRICT  ※ プロバイダー誤削除防止
--   * refund_of_event_id → 自己参照 SET NULL  ※ 同一テーブル内
--   * organization_id / operated_by_user_id: FK なし（INDEX のみ、クロスドメイン弱参照）
CREATE TABLE point_card_balance_events (
    id                    CHAR(36)        NOT NULL,
    card_id               CHAR(36)        NOT NULL,
    provider_id           CHAR(36)        NOT NULL,
    organization_id       BIGINT UNSIGNED NOT NULL,
    operation_type        VARCHAR(20)     NOT NULL,
    delta                 DECIMAL(12,2)   NOT NULL,
    balance_after         DECIMAL(12,2)   NOT NULL,
    refund_of_event_id    CHAR(36)        NULL,
    operated_by_user_id   BIGINT UNSIGNED NOT NULL,
    operated_at           DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    note                  VARCHAR(200)    NULL,
    created_at            DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_pcbe_card (card_id, operated_at DESC),
    INDEX idx_pcbe_org_operated_at (organization_id, operated_at DESC),
    INDEX idx_pcbe_provider (provider_id, operated_at DESC),
    INDEX idx_pcbe_operated_by (operated_by_user_id, operated_at DESC),
    INDEX idx_pcbe_refund_of (refund_of_event_id),
    CONSTRAINT fk_pcbe_card FOREIGN KEY (card_id) REFERENCES user_point_cards(id) ON DELETE CASCADE,
    CONSTRAINT fk_pcbe_provider FOREIGN KEY (provider_id) REFERENCES point_card_providers(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pcbe_refund_of FOREIGN KEY (refund_of_event_id) REFERENCES point_card_balance_events(id) ON DELETE SET NULL,
    CONSTRAINT chk_pcbe_operation_type CHECK (operation_type IN ('CHARGE', 'SPENT', 'REFUND')),
    CONSTRAINT chk_pcbe_delta_nonzero CHECK (delta <> 0),
    CONSTRAINT chk_pcbe_delta_range CHECK (delta >= -1000000.00 AND delta <= 1000000.00),
    CONSTRAINT chk_pcbe_balance_nonneg CHECK (balance_after >= 0),
    CONSTRAINT chk_pcbe_balance_max CHECK (balance_after <= 10000000.00)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
