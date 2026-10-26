-- F19.1 Phase 1 Foundation: サポーター向け氏名表示モード
-- 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §5.1 / §5.4
--
-- Phase 1 ではカラム追加のみ。機能活性化（IdentityVisibilityResolver の参照と
-- サポーター向け切替 API）は Phase 2 で実装する。

ALTER TABLE teams
  ADD COLUMN supporter_name_disclosure ENUM('DISPLAY_NAME','REAL_NAME')
  NOT NULL DEFAULT 'DISPLAY_NAME'
  COMMENT 'サポーター向け氏名表示モード（F19.1 Phase 2 で機能活性化、Phase 1 ではカラム追加のみ）';

ALTER TABLE organizations
  ADD COLUMN supporter_name_disclosure ENUM('DISPLAY_NAME','REAL_NAME')
  NOT NULL DEFAULT 'DISPLAY_NAME'
  COMMENT 'サポーター向け氏名表示モード（F19.1 Phase 2 で機能活性化、Phase 1 ではカラム追加のみ）';
