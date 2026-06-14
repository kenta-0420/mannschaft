-- F08.10 / 01 §B.5: match_sets（セット制スコア子表・バレーボール）。
--
-- セット制競技（バレー）のセットごとの得点・勝者を保持する。連続時間制（サッカー/バスケ）・
-- ターン制（将棋/囲碁）の試合は match_sets 行を持たない（セット制競技のみ使用・01 §B.5）。
--
-- 原則準拠（CLAUDE.md・01 §A.4 / §B.5）:
--   - 新規テーブルゆえ主キーは UUIDv7（原則6）。id は BINARY(16)。
--   - organization_id / deleted_at は【持たない】。テナント分離は親 matches で担保し、子は match_id
--     スコープでのみアクセスする二段アクセス（01 §A.4・IDOR 根絶）。子の削除は親 matches の CASCADE に従う。
--   - match_id → matches(id) は同一 match ドメイン内ゆえ FK＋ON DELETE CASCADE 可（原則2）。
--     クロスドメイン FK は張らない（原則1）。
--   - UNIQUE(match_id, set_number): 1 試合 1 セット番号は 1 行（upsert キー）。
--
-- スコア二層構造（sports/04_volleyball.md §4.1 / 01 §B.1.2）:
--   - セット内スコアの正本は本表（home_points/away_points/winner_side）。
--   - 獲得セット数（試合の本戦スコア）は matches.home_score/away_score に集計反映（Service が導出）。
--
-- 採番（CLAUDE.md / feedback_flyway_version_sort_after_global_max）:
--   matches CREATE は V76 系・state_model ALTER は V85 系。本 CREATE は origin/main 全体最大 major
--   （V85）の次（V86 系）を採る。V9.* 形式は major=9 として V10〜V85 より前にソートされ from-scratch で
--   死ぬため不可。
--   ※マージ直前に origin/main の最大 Flyway 番号を再確認しリネームすること（並行 PR 衝突回避・
--     feedback_migration_version_collision）。
--
-- 設計書: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.5
--   / sports/04_volleyball.md §3 / §4

CREATE TABLE match_sets (
    id           BINARY(16)         NOT NULL              COMMENT 'UUIDv7（原則6）',
    match_id     BINARY(16)         NOT NULL              COMMENT 'matches(id)（同一ドメイン → FK CASCADE）',
    set_number   SMALLINT UNSIGNED  NOT NULL              COMMENT 'セット番号（1〜5・best-of-5）',
    home_points  SMALLINT UNSIGNED  NOT NULL DEFAULT 0    COMMENT '当該セットのホーム得点',
    away_points  SMALLINT UNSIGNED  NOT NULL DEFAULT 0    COMMENT '当該セットのアウェイ得点',
    winner_side  ENUM('HOME','AWAY') NULL                 COMMENT 'セット勝者（SET_END でデュース条件達成時に確定・未決着は NULL・sports/04 §4.2）',
    is_final_set BOOLEAN            NOT NULL DEFAULT FALSE COMMENT '最終第 5 セット（15 点制・デュース）フラグ',
    created_at   DATETIME           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME           NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_match_sets (match_id, set_number),
    KEY idx_match_sets_match (match_id, set_number),
    CONSTRAINT fk_match_sets_match FOREIGN KEY (match_id)
        REFERENCES matches (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.10/01 §B.5 セット制スコア子表（バレー・テナント分離は親 matches）';
