-- F03.11 募集型予約: 個別キャンセル記録 (Phase 5a)
-- 永続保持。料金請求や紛争対応の証跡として使用。
-- v0.3.1: GDPR 削除時のため ON DELETE SET NULL + 論理削除フラグ
-- §14.11: fee_amount は INSERT 後 UPDATE 禁止 (アプリ層で強制)
CREATE TABLE recruitment_cancellation_records (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    participant_id      BIGINT UNSIGNED,
    listing_id          BIGINT UNSIGNED,
    user_id             BIGINT UNSIGNED,
    team_id             BIGINT UNSIGNED,
    cancelled_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_by        BIGINT UNSIGNED,
    cancel_source       VARCHAR(20)     NOT NULL,
    hours_before_start  INT UNSIGNED    NOT NULL,
    applied_tier_id     BIGINT UNSIGNED,
    fee_amount          INT UNSIGNED    NOT NULL DEFAULT 0,
    payment_status      VARCHAR(20)     NOT NULL DEFAULT 'NOT_REQUIRED',
    payment_id          VARCHAR(100),
    notes               VARCHAR(500),
    deleted_at          DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT fk_rcr_participant
        FOREIGN KEY (participant_id) REFERENCES recruitment_participants (id) ON DELETE SET NULL,
    CONSTRAINT fk_rcr_listing
        FOREIGN KEY (listing_id) REFERENCES recruitment_listings (id) ON DELETE SET NULL,
    CONSTRAINT fk_rcr_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_rcr_team
        FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE SET NULL,
    CONSTRAINT fk_rcr_cancelled_by
        FOREIGN KEY (cancelled_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_rcr_applied_tier
        FOREIGN KEY (applied_tier_id) REFERENCES recruitment_cancellation_policy_tiers (id) ON DELETE SET NULL,
    INDEX idx_rcr_participant (participant_id),
    INDEX idx_rcr_user_history (user_id, cancelled_at),
    INDEX idx_rcr_listing (listing_id, cancelled_at),
    INDEX idx_rcr_payment_status (payment_status, cancelled_at),
    INDEX idx_rcr_unpaid (user_id, payment_status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 依存順問題の解決: V3.119 で先行作成された recruitment_listings.cancellation_policy_id への FK を後付け
ALTER TABLE recruitment_listings
    ADD CONSTRAINT fk_rl_cancellation_policy
    FOREIGN KEY (cancellation_policy_id)
    REFERENCES recruitment_cancellation_policies (id)
    ON DELETE SET NULL;
