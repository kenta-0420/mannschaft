-- V218: CMP-260919-1140 Phase 1 メンバー統合画面（一覧／紹介）サブタブのロール別可視性テーブル
--
-- 上部タブ「メンバー」配下のサブタブ（一覧＝名簿／紹介）ごとに、閲覧可能な最低ロール（min_role）を
-- スコープ（チーム／組織）単位で管理者が設定できるようにする。既存 dashboard_widget_role_visibility
-- （F02.2.1）と同一パターン: レコードがないサブタブはアプリ層デフォルト値が適用される遅延作成方式。
--
-- min_role は既存 com.mannschaft.app.dashboard.MinRole（PUBLIC/SUPPORTER/MEMBER）を再利用する。
-- 一覧タブ（subtab_key='member_list'）は氏名・役割等を含むため PUBLIC 設定不可（Service 層で検証、422）。
--
-- クロスドメインFK禁止のため scope_id・updated_by には FK を張らず、参照整合性はアプリ層で保証する
-- （インデックスのみ。member ドメイン→auth ドメインへの FK は CLAUDE.md DB設計原則 #1 違反のため不可）。
-- 主キーは DB 設計原則 #6（新規テーブルは UUIDv7）に従い BINARY(16) とする。
-- 設計書: docs/features/F06.6_member_subtab_visibility.md §3, §9

CREATE TABLE member_subtab_role_visibility (
  id BINARY(16) NOT NULL,
  scope_type VARCHAR(20) NOT NULL,
  scope_id BIGINT UNSIGNED NOT NULL,
  subtab_key VARCHAR(30) NOT NULL,
  min_role VARCHAR(20) NOT NULL,
  updated_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_msrv_scope_subtab (scope_type, scope_id, subtab_key),
  INDEX idx_msrv_scope (scope_type, scope_id),
  INDEX idx_msrv_updated_by (updated_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 初期データ投入なし。レコードがないサブタブはアプリ層デフォルト（MEMBER）が適用される。
