-- F03.11 募集型予約: 参加者ステータス遷移履歴 (Phase 1)
-- Phase 1 では INSERT 最小限。Phase 3 で WAITLISTED / AUTO_CANCELLED 遷移時に本格活用
CREATE TABLE recruitment_participant_history (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    participant_id  BIGINT UNSIGNED NOT NULL,
    listing_id      BIGINT UNSIGNED NOT NULL,
    old_status      VARCHAR(20),
    new_status      VARCHAR(20)     NOT NULL,
    changed_by      BIGINT UNSIGNED,
    change_reason   VARCHAR(20)     NOT NULL,
    changed_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_rph_participant
        FOREIGN KEY (participant_id) REFERENCES recruitment_participants (id) ON DELETE CASCADE,
    CONSTRAINT fk_rph_listing
        FOREIGN KEY (listing_id) REFERENCES recruitment_listings (id) ON DELETE CASCADE,
    CONSTRAINT fk_rph_changed_by
        FOREIGN KEY (changed_by) REFERENCES users (id) ON DELETE SET NULL,
    INDEX idx_rph_listing (listing_id, changed_at),
    INDEX idx_rph_participant (participant_id, changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
