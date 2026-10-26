-- F10.3: refresh_tokens に jti カラムを追加し、session_hash 計算の基点とする
ALTER TABLE refresh_tokens
  ADD COLUMN jti VARCHAR(36) NOT NULL DEFAULT '',
  ADD KEY idx_rt_jti (jti);
