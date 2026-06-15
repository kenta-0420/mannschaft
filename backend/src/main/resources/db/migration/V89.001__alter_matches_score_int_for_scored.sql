-- F08.10 / 01 §B.1.2 / §D.8 / sports/07_scored.md §4.1: 採点競技（第 4 状態モデル類型 SCORED）対応。
--
-- 採点競技（フィギュアスケート/体操）は合計点に小数（例 198.45 点）を持つ。SMALLINT UNSIGNED（最大 65535）
-- では「整数スケール×1000」した合計点（6 桁以上・例 198450）を表現できないため、本戦スコア列
-- matches.home_score / away_score を SMALLINT UNSIGNED → INT UNSIGNED へ 1 回だけ拡張する。
--
--   - 全競技共通列の単純拡張であり、既存の球技/盤上スコア（小さな整数＝得点・獲得セット数・勝ち星・勝敗 1/0）に
--     完全に無害（値域が広がるだけ・既存値はそのまま保持される・後方互換）。
--   - 勝敗格納規約（§B.1.2）は不変: 勝敗は全競技で home_score/away_score の大小から resolveResult() で導出する。
--     採点競技は整数スケール×1000 の合計点を入れ、その大小で W/D/L を導出する（同点＝整数スケール同値は DRAW）。
--     スケール係数（×1000）は SCORED 類型に限り適用し、表示変換は FE/DTO 層で行う（コアの集計コードは整数の
--     大小だけ見るため改造不要・§4.1）。
--   - PK 戦スコア列（home_penalty_score / away_penalty_score）はサッカー専用で採点競技は使わないため拡張しない
--     （SMALLINT UNSIGNED のまま据え置き）。
--   - NULL 許容・既定値（DEFAULT なし）は変更しない（型のみ拡張）。
--
-- 採番（CLAUDE.md / feedback_flyway_version_sort_after_global_max / feedback_migration_version_collision）:
--   matches CREATE は V76 系・state_model 追加は V85・ターン制/添付は V87 系。本 ALTER は origin/main 全体
--   最大 major（V88）の次（V89 系）を採る。V9.* 形式は major=9 として V10〜V88 より前にソートされ
--   from-scratch で死ぬため不可。
--   ※マージ直前に origin/main の最大 Flyway 番号を再確認しリネームすること（並行 PR 衝突回避）。
--
-- 設計書: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.1.2 / §D.8
--         docs/features/F08.10_match_record_analytics/sports/07_scored.md §4.1

ALTER TABLE matches
    MODIFY COLUMN home_score INT UNSIGNED NULL
        COMMENT 'ホーム本戦スコア（正本・延長得点も合算。採点競技は合計点を整数スケール×1000 で格納・§B.1.2/§D.8）',
    MODIFY COLUMN away_score INT UNSIGNED NULL
        COMMENT 'アウェイ本戦スコア（正本・延長得点も合算。採点競技は合計点を整数スケール×1000 で格納・§B.1.2/§D.8）';
