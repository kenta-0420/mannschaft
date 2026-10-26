-- F15.4: 組織内チーム（店舗）検索の高速化
-- 設計書: docs/features/F15.4_team_store_search_within_org.md §2.3
--
-- 既存インデックスとの関係:
--   - idx_teams_visibility (V2.004): visibility 単独。本 Migration の (prefecture, city, visibility, archived_at) 複合インデックスとは別物
--   - idx_team_org_memberships_org (V62.006): organization_id 単独。本 Migration の (organization_id, status) 複合インデックスは status 絞り込み付きで別物

-- 後置フィルタ高速化（archived_at IS NULL を含む）
CREATE INDEX idx_teams_pref_city_visibility
  ON teams (prefecture, city, visibility, archived_at);

-- フリガナ昇順ソート用
CREATE INDEX idx_teams_name_kana ON teams (name_kana);

-- team_org_memberships の organization_id + status 複合インデックス補強
-- 組織配下のアクティブメンバーシップ抽出（status='ACTIVE'）を高速化
CREATE INDEX idx_team_org_memberships_org_id_status
  ON team_org_memberships (organization_id, status);
