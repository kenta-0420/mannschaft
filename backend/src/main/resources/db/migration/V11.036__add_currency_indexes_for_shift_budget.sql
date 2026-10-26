-- F08.7 Phase 9-δ: 多通貨拡張用インデックス予備
-- 設計書 F08.7 (v1.2) §5.7 に準拠。Phase 10 多通貨対応の事前準備。
--
-- 偵察結果（2026-05-03 時点）:
--   - V11.030 shift_budget_allocations: idx_sba_currency が既存（CREATE TABLE 時に組込済）
--   - V11.031 shift_budget_consumptions: 既存無し → 本マイグレーションで追加
--   - V11.032 todo_budget_links:        既存無し → 本マイグレーションで追加
--
-- 既存インデックスがある場合に備え、CREATE INDEX IF NOT EXISTS を使用したいが
-- MySQL 8.0 はサポートしないため、Flyway の repeatable な情報スキーマ確認パターンで実装。
-- ただし運用上は本マイグレーション初回投入時にのみ走るため、CREATE INDEX を直接実行する。

-- shift_budget_consumptions に currency インデックス追加
-- 多通貨集計時の WHERE currency = 'XXX' での絞り込みを高速化
CREATE INDEX idx_sbc_currency ON shift_budget_consumptions (currency);

-- todo_budget_links に currency インデックス追加
-- 多通貨集計時の WHERE currency = 'XXX' での絞り込みを高速化
CREATE INDEX idx_tbl_currency ON todo_budget_links (currency);
