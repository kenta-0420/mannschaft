-- organizations テーブルに URL 用 public_id を追加（列挙攻撃対策）
-- NULL許容で追加: STATEMENT binlog で UUID() が unsafe のためDEFAULT式は使わない
-- JPA側は @UuidGenerator で常に値を生成するため NULL になることはない（アプリ層保証）
-- テストフィクスチャ等のネイティブSQLインサートは NULL を許容（UNIQUE制約はNULLを許可）
ALTER TABLE organizations ADD COLUMN public_id BINARY(16) NULL;
UPDATE organizations SET public_id = UUID_TO_BIN(UUID(), 1) WHERE public_id IS NULL;
ALTER TABLE organizations ADD UNIQUE INDEX idx_organizations_public_id (public_id);
