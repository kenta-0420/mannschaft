-- F15.4 Phase 4: teams テーブルにメンバー数集計カラムを追加
-- リスナー（足軽16）で同期更新、夜次バッチ（足軽17）で誤差補正する事前集計方式
-- 設計書: docs/features/F15.4_team_store_search_within_org.md §3.3 / §11.4
--
-- user_roles テーブルには status カラムが存在しないため、active 判定は
-- 「user_roles 行が存在する=アクティブ」として team_id 単位で COUNT する。
-- ユーザー側の論理削除/匿名化との整合はリスナー（足軽16）が責務を持つ。

ALTER TABLE teams
  ADD COLUMN member_count BIGINT NOT NULL DEFAULT 0
    COMMENT 'F15.4: アクティブメンバー数の事前集計（user_roles.team_id COUNT）。リスナーで同期、夜次バッチで補正';

-- 初期値を集計（user_roles に基づく COUNT）
UPDATE teams t
SET t.member_count = (
  SELECT COUNT(*) FROM user_roles ur
  WHERE ur.team_id = t.id
);

-- 検索や並び替えで使う場合に備えてインデックス追加
CREATE INDEX idx_teams_member_count ON teams (member_count);
