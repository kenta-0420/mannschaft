-- F03.15 個人時間割: メモ添付ファイル（最大5件/ノート、R2 ストレージ）
CREATE TABLE IF NOT EXISTS timetable_slot_user_note_attachments (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    note_id           BIGINT UNSIGNED NOT NULL                COMMENT 'FK → timetable_slot_user_notes.id',
    user_id           BIGINT UNSIGNED NOT NULL                COMMENT 'FK → users.id（認可ショートサーキット用に冗長保持）',
    r2_object_key     VARCHAR(500)    NOT NULL                COMMENT 'R2 オブジェクトキー user/{user_id}/timetable-notes/{uuid}',
    original_filename VARCHAR(255)    NOT NULL                COMMENT '元ファイル名',
    mime_type         VARCHAR(100)    NOT NULL                COMMENT 'MIME タイプ',
    size_bytes        INT UNSIGNED    NOT NULL                COMMENT 'バイトサイズ',
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at        DATETIME        NULL                    COMMENT '論理削除（30日後に R2 物理削除）',

    PRIMARY KEY (id),
    INDEX idx_tsuna_note (note_id),
    INDEX idx_tsuna_user (user_id),

    CONSTRAINT fk_tsuna_note FOREIGN KEY (note_id) REFERENCES timetable_slot_user_notes(id) ON DELETE CASCADE,
    CONSTRAINT fk_tsuna_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='F03.15 メモ添付ファイル';
