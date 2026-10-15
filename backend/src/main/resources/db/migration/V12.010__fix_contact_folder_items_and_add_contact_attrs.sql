-- chat_contact_folder_items: UNIQUE KEY バグ修正 + 連絡先属性カラム追加
-- 旧 UNIQUE KEY (item_type, item_id) は全ユーザー横断制約になるため修正する
ALTER TABLE chat_contact_folder_items
    DROP INDEX uq_ccfi_item,
    ADD UNIQUE KEY uq_ccfi_folder_item (folder_id, item_type, item_id),
    ADD COLUMN custom_name  VARCHAR(50)  NULL     COMMENT '連絡先の任意表示名（本名の代わりに表示）' AFTER item_id,
    ADD COLUMN is_pinned    BOOLEAN      NOT NULL DEFAULT FALSE COMMENT 'お気に入り（ピン留め）フラグ' AFTER custom_name,
    ADD COLUMN private_note VARCHAR(500) NULL     COMMENT 'プライベートメモ（他ユーザーには非公開）' AFTER is_pinned;
