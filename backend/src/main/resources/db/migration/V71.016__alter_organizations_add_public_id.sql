-- organizations テーブルに URL 用 public_id を追加（列挙攻撃対策）
ALTER TABLE organizations ADD COLUMN public_id BINARY(16);
UPDATE organizations SET public_id = UUID_TO_BIN(UUID(), 1) WHERE public_id IS NULL;
ALTER TABLE organizations MODIFY COLUMN public_id BINARY(16) NOT NULL;
ALTER TABLE organizations ADD UNIQUE INDEX idx_organizations_public_id (public_id);
