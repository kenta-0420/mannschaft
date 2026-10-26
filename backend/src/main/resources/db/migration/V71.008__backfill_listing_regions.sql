-- F22.1 Phase2 D 複数地域募集（N:N）: 既存札の地域を中間表へバックフィル。
--
-- V71.001 で recruitment_listings に追加した単一列（prefecture_code / city_code）に値を持つ
-- 既存札を、新しい中間表 recruitment_listing_regions へ代表 1 行としてコピーする。
-- これにより「単一地域で立てた既存札」も新しい N:N 検索・件数集計の経路に乗る。
--
-- 県必須化: 中間表の prefecture_code は NOT NULL のため、単一列で prefecture_code が NULL でも
--   city_code が非 NULL なら上位 2 桁（COALESCE(prefecture_code, SUBSTRING(city_code,1,2))）で県を補完する。
-- 対象: 単一列のいずれかが非 NULL かつ deleted_at IS NULL の札のみ（地域なし札はコピーしない）。
-- 移行行 PK: UNHEX(REPLACE(UUID(),'-','')) による UUID v4。中間表ゆえ時刻順整列は不要（D 設計裁可済み）。
-- 冪等性: UNIQUE(listing_id, prefecture_code, city_code) に対し NOT EXISTS で二重コピーを防ぐ。
--   旧単一列はこのバックフィル後も残置し、Service 層が代表 1 件を同期し続ける（後方互換読み）。

INSERT INTO recruitment_listing_regions (id, listing_id, prefecture_code, city_code, created_at)
SELECT
    UNHEX(REPLACE(UUID(), '-', '')),
    l.id,
    COALESCE(l.prefecture_code, SUBSTRING(l.city_code, 1, 2)),
    l.city_code,
    NOW(6)
FROM recruitment_listings l
WHERE l.deleted_at IS NULL
  AND (l.prefecture_code IS NOT NULL OR l.city_code IS NOT NULL)
  AND NOT EXISTS (
      SELECT 1 FROM recruitment_listing_regions r
      WHERE r.listing_id = l.id
        AND r.prefecture_code <=> COALESCE(l.prefecture_code, SUBSTRING(l.city_code, 1, 2))
        AND r.city_code <=> l.city_code
  );
