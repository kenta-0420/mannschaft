-- F19.1 Phase 6: users テーブルに公開プロフィール有効フラグを追加
-- 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.6 Phase 6
-- ユーザーが true に設定すると /api/v1/public/users/{userId} で
-- 未ログイン訪問者からもプロフィール・公開投稿一覧を閲覧できるようになる。
ALTER TABLE users
  ADD COLUMN public_profile_enabled BOOLEAN NOT NULL DEFAULT FALSE;
