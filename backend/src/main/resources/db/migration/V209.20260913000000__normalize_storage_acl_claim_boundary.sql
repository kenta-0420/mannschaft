-- Storage ACL の所有スコープ、親認可参照、添付束縛先を分離する。
-- V192 は既適用のため変更せず、legacy producer の監査列も第1陣では保持する。
ALTER TABLE storage_acls
ALTER TABLE storage_acls
    ADD COLUMN scope_key VARCHAR(64) NULL AFTER scope_type,
    ADD COLUMN parent_content_reference_type VARCHAR(64) NULL AFTER content_type,
    ADD COLUMN parent_content_reference_key VARCHAR(64) NULL AFTER parent_content_reference_type,
    ADD COLUMN attachment_binding_type VARCHAR(64) NULL AFTER parent_content_reference_key,
    ADD COLUMN attachment_binding_key VARCHAR(64) NULL AFTER attachment_binding_type;

UPDATE storage_acls
   SET scope_key = CAST(scope_id AS CHAR),
       parent_content_reference_type = reference_type,
       parent_content_reference_key = CAST(reference_id AS CHAR);

ALTER TABLE storage_acls
    MODIFY COLUMN scope_key VARCHAR(64) NOT NULL,
    MODIFY COLUMN scope_id BIGINT UNSIGNED NULL,
    ADD KEY idx_storage_acls_scope_key (scope_type, scope_key);
