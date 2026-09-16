-- 柱①「ADMINゼロ根治」§12.5 — purge×cancel-withdrawalの勝敗判定用マーカー。
-- 正本: docs/architecture/account_purge_last_admin_succession.md §12.5。
-- purge開始マーク後はcancel-withdrawalを拒否し、マーク前はcancelを勝たせる。
ALTER TABLE users
    ADD COLUMN purge_started_at TIMESTAMP NULL COMMENT '柱①ADMINゼロ根治: purge開始マーク（§12.5）' AFTER purged_at;
