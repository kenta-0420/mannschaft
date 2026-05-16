-- F18 Phase 2: スタンプ押印履歴（証拠ログ）
-- 設計書: docs/features/F18_point_card_wallet.md §12.2 / §5 拡張テーブル
--
-- 同一機能内（pointcard ドメイン）のため card_id / provider_id の FK は許容（CLAUDE.md 原則 2）。
-- card_id ON DELETE CASCADE: カード削除時に履歴も消える（顧客側の証跡は user_point_cards 削除を伴う運用）。
-- provider_id ON DELETE RESTRICT: 履歴の整合性を運営承認が確認した後に消えるよう RESTRICT で守る。
-- organization_id / pressed_by_user_id はクロスドメイン参照のため FK なし（CLAUDE.md 原則 1）。
CREATE TABLE point_card_stamp_events (
    id                   CHAR(36)        NOT NULL,
    card_id              CHAR(36)        NOT NULL,
    provider_id          CHAR(36)        NOT NULL,
    organization_id      BIGINT UNSIGNED NOT NULL,
    delta                INT             NOT NULL,
    pressed_by_user_id   BIGINT UNSIGNED NOT NULL,
    pressed_at           DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    memo                 VARCHAR(200)    NULL,
    created_at           DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_pcse_card (card_id, pressed_at DESC),
    INDEX idx_pcse_org_pressed_at (organization_id, pressed_at DESC),
    INDEX idx_pcse_provider (provider_id, pressed_at DESC),
    INDEX idx_pcse_pressed_by (pressed_by_user_id, pressed_at DESC),
    CONSTRAINT fk_pcse_card FOREIGN KEY (card_id) REFERENCES user_point_cards(id) ON DELETE CASCADE,
    CONSTRAINT fk_pcse_provider FOREIGN KEY (provider_id) REFERENCES point_card_providers(id) ON DELETE RESTRICT,
    CONSTRAINT chk_pcse_delta_nonzero CHECK (delta <> 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
