-- teams テーブルに URL 用 public_id を追加（列挙攻撃対策）
-- DEFAULT式付与: JPA外からのINSERT（テストデータ等）でも自動生成される（MySQL 8.0.13+）
ALTER TABLE teams ADD COLUMN public_id BINARY(16) DEFAULT (UUID_TO_BIN(UUID(), 1));
UPDATE teams SET public_id = UUID_TO_BIN(UUID(), 1) WHERE public_id IS NULL;
ALTER TABLE teams MODIFY COLUMN public_id BINARY(16) NOT NULL DEFAULT (UUID_TO_BIN(UUID(), 1));
ALTER TABLE teams ADD UNIQUE INDEX idx_teams_public_id (public_id);
