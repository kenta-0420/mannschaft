-- サイネージ アクセストークンに有効期限カラムを追加する。
--
-- 発行時に指定された有効期限を永続化し、表示API（GET /signage/{token}）の
-- トークン検証で有効期限の満了を判定できるようにする。
-- NULL は無期限を意味する（既存行はすべて無期限として扱う）。
ALTER TABLE signage_access_tokens
    ADD COLUMN expired_at DATETIME NULL COMMENT 'トークン有効期限。NULLは無期限' AFTER allowed_ips;
