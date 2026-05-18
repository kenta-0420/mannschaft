-- F19.1 Phase 1 Foundation: チームイベントを公開ページに表示するか
-- 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §5.1 / §5.4
--
-- Phase 1 ではカラム追加のみ。機能活性化（公開ページからのチームイベント一覧
-- 公開と PublicTeamEventQueryService の参照）は Phase 4 で実装する。
-- 組織イベントは常時公開のため organizations 側には追加しない。

ALTER TABLE teams
  ADD COLUMN public_events_enabled BOOLEAN
  NOT NULL DEFAULT FALSE
  COMMENT '公開ページでチームイベント一覧を表示するか（F19.1 Phase 4 で機能活性化、Phase 1 ではカラム追加のみ）';
