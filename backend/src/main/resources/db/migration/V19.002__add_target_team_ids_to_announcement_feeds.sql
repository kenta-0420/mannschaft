-- F02.8 ダッシュボード告知ウィザード: 組織告知のチーム絞り込みカラム追加
ALTER TABLE announcement_feeds
    ADD COLUMN target_team_ids JSON NULL COMMENT '組織告知でのチーム絞り込み（NULL=全チーム対象）。TEAM スコープでは常に NULL。F02.8 §7 参照'
        AFTER visibility,
    ADD INDEX idx_af_target_teams ((CAST(target_team_ids -> '$[*]' AS UNSIGNED ARRAY)));
-- MySQL 8.0.17+ の multi-valued functional index。JSON_CONTAINS(target_team_ids, CAST(:teamId AS JSON)) で利用される
