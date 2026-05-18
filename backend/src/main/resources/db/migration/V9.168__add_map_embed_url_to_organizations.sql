-- F19.1 Phase 1 Foundation: 組織公開ページの地図埋め込み URL
-- 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §5.1 / §5.4
--
-- Phase 1 で機能活性化（organizations 公開ページの iframe 表示）。
-- バリデーション（^https://www\.google\.com/maps/embed\? で始まる）は Application 層で実施する。
-- teams.map_embed_url は F15.4 Phase 5-β V9.160__add_teams_map_embed_url.sql で
-- 先行導入済（main マージ済 2026-05-17）のため本マイグレーションでは追加しない。

ALTER TABLE organizations
  ADD COLUMN map_embed_url VARCHAR(2048)
  NULL
  COMMENT 'Google Maps 等の埋め込み URL（F19.1 Phase 1 で organizations の公開ページに表示）';
