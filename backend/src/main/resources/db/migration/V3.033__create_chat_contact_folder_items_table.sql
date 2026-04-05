-- F02.2: チャット・連絡先フォルダアイテム割り当てテーブル
CREATE TABLE chat_contact_folder_items (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    folder_id   BIGINT UNSIGNED NOT NULL,
    item_type   VARCHAR(20)     NOT NULL COMMENT 'DM_CHANNEL / CONTACT',
    item_id     BIGINT UNSIGNED NOT NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ccfi_item (item_type, item_id),
    INDEX idx_ccfi_folder (folder_id),
    CONSTRAINT fk_ccfi_folder FOREIGN KEY (folder_id) REFERENCES chat_contact_folders (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
