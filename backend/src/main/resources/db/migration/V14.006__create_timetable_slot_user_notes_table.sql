-- F03.15 個人時間割: コマ単位の個人メモ（チーム/個人スロット両方に紐付く統一テーブル）
-- slot_kind による多態参照のため slot_id への FK は貼らない（イベント駆動でクリーンアップ）。
CREATE TABLE timetable_slot_user_notes (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id         BIGINT UNSIGNED NOT NULL                COMMENT 'FK → users.id（所有者）',
    slot_kind       VARCHAR(10)     NOT NULL                COMMENT 'TEAM / PERSONAL',
    slot_id         BIGINT UNSIGNED NOT NULL                COMMENT 'TEAM=timetable_slots.id, PERSONAL=personal_timetable_slots.id（論理参照）',
    preparation     TEXT            NULL                    COMMENT '予習メモ（最大2,000字）',
    review          TEXT            NULL                    COMMENT '復習メモ（最大2,000字）',
    items_to_bring  TEXT            NULL                    COMMENT '持参物メモ（最大2,000字）',
    free_memo       TEXT            NULL                    COMMENT '自由メモ（最大10,000字）',
    custom_fields   JSON            NULL                    COMMENT 'カスタムフィールド値 [{"field_id":1,"value":"..."}]',
    target_date     DATE            NULL                    COMMENT '日付限定メモ（NULL=常設メモ）',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME        NULL                    COMMENT '論理削除',

    PRIMARY KEY (id),
    UNIQUE KEY uq_tsun_user_slot_date (user_id, slot_kind, slot_id, target_date),
    INDEX idx_tsun_user_slot (user_id, slot_kind, slot_id),
    INDEX idx_tsun_user_date (user_id, target_date),

    CONSTRAINT fk_tsun_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_tsun_slot_kind CHECK (slot_kind IN ('TEAM', 'PERSONAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='F03.15 コマ単位の個人メモ（統一テーブル）';
