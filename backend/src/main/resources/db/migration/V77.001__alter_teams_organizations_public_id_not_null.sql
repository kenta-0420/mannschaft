-- teams / organizations の public_id を NOT NULL 化
-- （V71.20260604160033/34 で追加済みだが nullable = true のまま残っていた）

-- 残存する NULL をバックフィル（V71 マイグレーション未実行環境向け安全網）
UPDATE teams SET public_id = UUID_TO_BIN(UUID(), 1) WHERE public_id IS NULL;
UPDATE organizations SET public_id = UUID_TO_BIN(UUID(), 1) WHERE public_id IS NULL;

-- NOT NULL 制約を付与
ALTER TABLE teams MODIFY COLUMN public_id BINARY(16) NOT NULL;
ALTER TABLE organizations MODIFY COLUMN public_id BINARY(16) NOT NULL;
