-- F22.1 市（Market）Phase 2 足場C 第一陣 ロールバック:
-- V71.004 で追加した teams の地域コード列とインデックスを撤去する。
-- 緊急時のみ手動実行（Flyway 管理外）。Expand のみのため完全に可逆。

DROP INDEX idx_teams_region ON teams;

ALTER TABLE teams
    DROP COLUMN city_code,
    DROP COLUMN prefecture_code;
