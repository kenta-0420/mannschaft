-- F08.10 / 01 §D.6 / §B.1: 多競技の状態モデル類型（StateModel）対応。
--
-- matches に state_model 列を追加し、match_events.period を NULL 許容化する。
--
--   - matches.state_model VARCHAR(16) NOT NULL DEFAULT 'CONTINUOUS_TIME'
--       状態モデル類型（CONTINUOUS_TIME/SET_BASED/TURN_BASED）。Sport から導出可だが
--       Service/FE の分岐を冪等かつ高速に行うため列としても保持する（01 §D.6）。
--       既存レコード（V76 で投入された SOCCER 試合）は DEFAULT 'CONTINUOUS_TIME' で充填され、
--       SOCCER=CONTINUOUS_TIME と一致するため後方互換（追加の UPDATE 不要）。
--   - match_events.period を NOT NULL → NULL 許容へ変更（01 §B.2 / §D.6）。
--       ターン制（将棋/囲碁）は period を使わないため NULL を許容する。連続時間制/セット制では
--       Service が period を必須化する（DDL は緩め・Service で締める）。
--       既存 period 値は維持され、NULL 化は許容範囲の緩和のみゆえ既存データに影響しない（後方互換）。
--
-- 採番（CLAUDE.md / feedback_flyway_version_sort_after_global_max）:
--   matches CREATE は V76 系のため、本 ALTER は全体最大 major の次（V85 系）を採る。
--   V9.* 形式は major=9 として V10〜V84 より前にソートされ from-scratch で死ぬため不可。
--   ※マージ直前に origin/main の最大 Flyway 番号を再確認しリネームすること（並行 PR 衝突回避・
--     feedback_migration_version_collision）。
--
-- 設計書: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §D.6 / §B.1 / §B.2

ALTER TABLE matches
    ADD COLUMN state_model VARCHAR(16) NOT NULL DEFAULT 'CONTINUOUS_TIME'
        COMMENT '状態モデル類型（CONTINUOUS_TIME/SET_BASED/TURN_BASED・Sport から導出・01 §D.6）'
        AFTER status;

ALTER TABLE match_events
    MODIFY COLUMN period VARCHAR(24) NULL
        COMMENT 'PeriodType（器は競技非依存）。ターン制（将棋/囲碁）は period を使わないため NULL 許容。連続時間制/セット制では Service が必須化する（01 §B.2 / §D.6）';
