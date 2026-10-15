-- F08.7 Phase 9: tournament_entry_members テーブル作成
-- 大会参加チームのエントリーメンバー（試合出場可能選手リスト）を管理する。
-- user_id は users テーブルへのクロスドメイン参照のため FK なし（アプリ層で整合性保証）。
CREATE TABLE tournament_entry_members (
    id             CHAR(36)          NOT NULL,
    participant_id BIGINT UNSIGNED   NOT NULL,
    user_id        BIGINT UNSIGNED   NOT NULL COMMENT 'クロスドメイン参照: users.id（FK なし）',
    jersey_number  SMALLINT UNSIGNED NULL,
    position       VARCHAR(30)       NULL,
    notes          VARCHAR(200)      NULL,
    sort_order     SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    created_at     DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE INDEX uq_tem_participant_user (participant_id, user_id),
    INDEX idx_tem_participant (participant_id, sort_order),
    INDEX idx_tem_user (user_id),
    CONSTRAINT fk_tem_participant FOREIGN KEY (participant_id)
        REFERENCES tournament_participants (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
