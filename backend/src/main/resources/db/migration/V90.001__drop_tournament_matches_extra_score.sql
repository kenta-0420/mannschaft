-- F08.10 Phase5b-3（Contract）: tournament_matches から延長別スコア列を削除する。
--
-- 延長得点は本戦スコア（home_score/away_score）へ合算済みであり、延長別列
-- （home_extra_score/away_extra_score）は不要（05 §H.1 移行表・sports/01_soccer.md §4.1）。
-- 勝敗判定・順位・楽観ロックは本戦スコアで完結するため、本列の削除で振る舞いは不変。
--
-- グリーンフィールド（既存データなし）ゆえデータ移行 DML は不要。
-- 万一既存行があっても延長得点は本戦に合算済みのため、列削除でスコア欠落は起きない。

ALTER TABLE tournament_matches
    DROP COLUMN home_extra_score,
    DROP COLUMN away_extra_score;
