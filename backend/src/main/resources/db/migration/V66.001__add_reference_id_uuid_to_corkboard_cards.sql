-- F09.15/16 S0: F00 ContentVisibilityResolver の UUIDv7 reference 対応（F00-A 案）
--
-- 背景:
--   CLAUDE.md 原則 6（2026-05-11 改訂）により、F09.15「区分所有者承継支援」/
--   F09.16「居住実態管理」が新設する 11 テーブルは UUIDv7 主キー（BINARY(16)）で設計される。
--   一方、F00 共通可視性判定基盤（ContentVisibilityResolver）が参照する既存
--   corkboard_cards.reference_id は BIGINT UNSIGNED であり、UUIDv7 を直接受けられない。
--
-- 採用方針: F00-A 案 — 並列カラム追加（docs/features/F00_content_visibility_resolver.md §3.4）
--   * 既存 reference_id BIGINT UNSIGNED はそのまま継続（後方互換）
--   * reference_id_uuid BINARY(16) NULL を並列追加
--   * reference_type ごとに「BIGINT 経路」「UUID 経路」のどちらを使うかを規約で決定
--   * いずれか一方のみが NOT NULL（XOR）— ただし「両方 NULL」は許容（純メモ/URL カードのため）
--
-- 影響範囲:
--   * 既存データはすべて reference_id を使っているため、本マイグレーションで既存違反なし
--   * F09.15/16 用 reference_type（SUCCESSION_PRE_REGISTRATION 等）が登場した時点で reference_id_uuid を使う
--   * 既存 Resolver の挙動は変更しない（既存 reference_type は引き続き reference_id を参照）

ALTER TABLE corkboard_cards
    ADD COLUMN reference_id_uuid BINARY(16) NULL AFTER reference_id,
    ADD INDEX idx_cc_reference_uuid (reference_type, reference_id_uuid);

-- XOR 制約: reference_id と reference_id_uuid は両方 NOT NULL になってはならない。
-- ただし「両方 NULL」は許容する（card_type=NOTE / URL / HEADING など参照を伴わないカード）。
-- MySQL 8.0.16+ で CHECK 制約が有効。
ALTER TABLE corkboard_cards
    ADD CONSTRAINT chk_cc_reference_xor
        CHECK (reference_id IS NULL OR reference_id_uuid IS NULL);
