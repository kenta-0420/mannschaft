-- F03.11 Phase 5b: 無断キャンセル（NO_SHOW）記録テーブル
-- 管理者マーク・自動検出された NO_SHOW を永続記録する。
-- confirmed=FALSE の間は24時間の仮マーク期間。
CREATE TABLE recruitment_no_show_records (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    participant_id     BIGINT UNSIGNED NOT NULL COMMENT '対象の参加者レコードID',
    listing_id         BIGINT UNSIGNED NOT NULL COMMENT '対象の募集枠ID',
    user_id            BIGINT UNSIGNED NOT NULL COMMENT '対象ユーザーID',
    reason             ENUM('ADMIN_MARKED', 'AUTO_DETECTED') NOT NULL COMMENT 'NO_SHOW検出理由',
    confirmed          BOOLEAN        NOT NULL DEFAULT FALSE COMMENT '24h仮マーク期間経過後にTRUE',
    recorded_at        DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'マーク日時',
    recorded_by        BIGINT UNSIGNED NULL     COMMENT 'マーク実施者ユーザーID（自動検出時NULL）',
    disputed           BOOLEAN        NOT NULL DEFAULT FALSE COMMENT '異議申立済みフラグ',
    dispute_resolution ENUM('UPHELD', 'REVOKED') NULL COMMENT '異議申立結果',
    created_at         DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_rns_participant FOREIGN KEY (participant_id) REFERENCES recruitment_participants (id) ON DELETE CASCADE,
    CONSTRAINT fk_rns_listing    FOREIGN KEY (listing_id)     REFERENCES recruitment_listings (id)     ON DELETE CASCADE,
    CONSTRAINT fk_rns_user       FOREIGN KEY (user_id)        REFERENCES users (id)                    ON DELETE CASCADE,
    INDEX idx_rns_user_id   (user_id),
    INDEX idx_rns_listing   (listing_id),
    INDEX idx_rns_confirmed (confirmed, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F03.11 Phase5b: 無断キャンセル記録';
