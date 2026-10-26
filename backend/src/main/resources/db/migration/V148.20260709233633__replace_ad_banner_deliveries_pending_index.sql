-- F09.19.3: ad_banner_deliveries の未表示予約サービング用インデックス置換（正本 §5.2 V144.004 のインデックス分）
--
-- 背景: pull 型サービング（§7.2 STEP 1）は「served_at IS NULL の未表示予約を created_at 昇順で 1 件取得」する。
-- 既存 idx_abd_user (user_id, served_at) は新インデックス (user_id, served_at, created_at) のプレフィクスと
-- 重複するため、created_at を含む複合インデックスへ置換して予約取得クエリを効率化する。
--
-- NOTE: 列の NULL 許容化（ad_impression_id / served_at の MODIFY）は .2 の
-- V144.20260708013355__relax_ad_banner_deliveries_for_pull_serving.sql で実施済み。
-- 二重定義を避けるため本 migration ではインデックス置換のみを行う。
ALTER TABLE ad_banner_deliveries DROP INDEX idx_abd_user;
CREATE INDEX idx_abd_user_pending ON ad_banner_deliveries (user_id, served_at, created_at);
