-- F03.11 募集型予約: キャンセル料の段階定義 (Phase 5a)
-- 1ポリシーあたり最大4段階。tier_order が大きいほど開催直前で高料金
CREATE TABLE recruitment_cancellation_policy_tiers (
    id                          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    policy_id                   BIGINT UNSIGNED  NOT NULL,
    tier_order                  TINYINT UNSIGNED NOT NULL,
    applies_at_or_before_hours  INT UNSIGNED     NOT NULL,
    fee_type                    VARCHAR(20)      NOT NULL,
    fee_value                   INT UNSIGNED     NOT NULL,
    created_at                  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at                  DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT fk_rcpt_policy
        FOREIGN KEY (policy_id) REFERENCES recruitment_cancellation_policies (id) ON DELETE CASCADE,
    UNIQUE KEY uk_rcpt_policy_order (policy_id, tier_order, deleted_at),
    INDEX idx_rcpt_policy_lookup (policy_id, applies_at_or_before_hours),
    CONSTRAINT chk_rcpt_tier_order CHECK (tier_order BETWEEN 1 AND 4),
    CONSTRAINT chk_rcpt_fee_value CHECK (
        (fee_type = 'PERCENTAGE' AND fee_value BETWEEN 1 AND 100) OR
        (fee_type = 'FIXED' AND fee_value > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
