-- F15.4 Phase 5-β: 店舗詳細ページの地図表示用に Google Maps 埋め込み URL を保存
-- 設計書: docs/features/F15.4_phase5_team_public_detail.md §5
-- バリデーションは Application 層で実施（^https://www\.google\.com/maps/embed\? で開始）

ALTER TABLE teams
  ADD COLUMN map_embed_url VARCHAR(2048) NULL COMMENT 'Google Maps 埋め込み URL（未ログイン公開店舗詳細ページで iframe 表示）';
