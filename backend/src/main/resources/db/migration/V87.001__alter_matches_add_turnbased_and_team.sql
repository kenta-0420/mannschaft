-- F08.10 / 01 §B.1 / §B.6 / §D.7: ターン制（将棋/囲碁）＋団体戦対応の matches 列追加。
--
-- matches に 4 列を追加する（ターン制 2 列・団体戦 2 列）。既存行（SOCCER/VOLLEYBALL 等）は
-- すべて NULL で充填され後方互換（球技はこれらの列を使わない・連続/セット制の COMPLETED 判定は不変）。
--
--   - total_moves SMALLINT UNSIGNED NULL
--       総手数（ターン制のみ・球技では NULL・sports/05_shogi.md §3）。
--   - win_method VARCHAR(32) NULL
--       勝ち方（ターン制のみ・競技別カタログ enum 文字列＝ShogiWinMethod/GoWinMethod・§D.7・球技では NULL）。
--       VARCHAR(32)＝将来の長い enum 名の余地を確保。
--   - parent_match_id BINARY(16) NULL
--       団体戦の親 match（個人戦=NULL／団体戦の子ボードのみ設定・§B.6）。matches → matches の
--       同一テーブル自己参照（同一 match ドメイン内）。CLAUDE.md 原則 2「CASCADE DELETE は同一ドメイン内のみ許可」
--       に合致するため ON DELETE CASCADE を張る（親団体戦の物理削除で子ボードも消える正しいセマンティクス・§B.1 注記）。
--   - board_number SMALLINT UNSIGNED NULL
--       ボード順（団体戦の子のみ・1=大将/主将 等・§B.6）。
--
-- インデックス・制約（01 §B.1 DDL）:
--   - INDEX idx_matches_parent (parent_match_id, board_number): 団体戦の子ボード取得（§B.6・親 ID から子一覧）。
--   - CONSTRAINT fk_matches_parent: 自己参照 FK＋ON DELETE CASCADE（同一ドメイン・原則 2）。
--
-- 採番（CLAUDE.md / feedback_flyway_version_sort_after_global_max）:
--   matches CREATE は V76 系・state_model ALTER は V85 系・match_sets CREATE は V86 系。本 ALTER は
--   origin/main 全体最大 major（V86）の次（V87 系）を採る。V9.* 形式は major=9 として V10〜V86 より前に
--   ソートされ from-scratch で死ぬため不可。既存 matches テーブルへの ALTER は matches CREATE（V76）より
--   後の番号でなければ死ぬため、必ず全体最大の次の major を採ること。
--   ※マージ直前に origin/main の最大 Flyway 番号を再確認しリネームすること（並行 PR 衝突回避・
--     feedback_migration_version_collision）。
--
-- 設計書: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.1 / §B.6 / §D.7
--   / sports/05_shogi.md §3 / §4 / sports/06_go.md §4

ALTER TABLE matches
    ADD COLUMN total_moves SMALLINT UNSIGNED NULL
        COMMENT '総手数（ターン制のみ・球技では NULL・01 §B.1 / sports/05 §3）'
        AFTER state_model,
    ADD COLUMN win_method VARCHAR(32) NULL
        COMMENT '勝ち方（ターン制のみ・ShogiWinMethod/GoWinMethod・球技では NULL・01 §D.7）'
        AFTER total_moves,
    ADD COLUMN parent_match_id BINARY(16) NULL
        COMMENT '団体戦の親 match（個人戦=NULL・自己参照 FK＋CASCADE・同一ドメイン・01 §B.6）'
        AFTER win_method,
    ADD COLUMN board_number SMALLINT UNSIGNED NULL
        COMMENT 'ボード順（団体戦の子のみ・1=大将/主将 等・01 §B.6）'
        AFTER parent_match_id;

ALTER TABLE matches
    ADD INDEX idx_matches_parent (parent_match_id, board_number);

ALTER TABLE matches
    ADD CONSTRAINT fk_matches_parent FOREIGN KEY (parent_match_id)
        REFERENCES matches (id) ON DELETE CASCADE;
