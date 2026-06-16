-- F08.10 / sports/07_scored.md §4B / 01 §B.1.2 / §D.8: match_scored_components
-- （採点競技＝フィギュアスケート/体操の審判別/種目別採点内訳子表）。
--
-- 採点競技（SCORED）の MVP は合計点のみ（matches.home_score/away_score・整数スケール×1000）だが、
-- 「審判別/種目別/コンポーネント別の内訳を残したい」要件に応える後段 Phase の子表。
-- 内訳の正本は本表、合計点（試合の本戦スコア）は matches.home_score/away_score に再導出反映する二層正本
-- （match_sets〔セット内得点→獲得セット数〕・団体戦〔子ボード勝ち星→親列〕と同じパターン・§4B.2 / §B.1.2）。
--
-- 原則準拠（CLAUDE.md・01 §A.4 / §B.5 / sports/07_scored.md §4B.1）:
--   - 新規テーブルゆえ主キーは UUIDv7（原則6）。id は BINARY(16)。
--   - organization_id / deleted_at は【持たない】。テナント分離は親 matches で担保し、子は match_id
--     スコープでのみアクセスする二段アクセス（01 §A.4・IDOR 根絶）。子の削除は親 matches の CASCADE に従う。
--   - match_id → matches(id) は同一 match ドメイン内ゆえ FK＋ON DELETE CASCADE 可（原則2）。
--     クロスドメイン FK は張らない（原則1）。
--
-- 列の使い分け（sports/07_scored.md §4B.1 / §4B.2）:
--   - competitor_side: 2 者対戦（MVP）の side（HOME/AWAY）で内訳を束ねる。
--   - score_entry_id : 多人数順位制（§5B・後段 Phase・別タスク）のエントリ参照（2 者対戦時は NULL）。
--                      本タスクでは未使用だが設計済 DDL どおり列を用意する（match_score_entries は未作成のため FK は張らない）。
--   - apparatus      : 種目/セグメント（体操 FLOOR/POMMEL_HORSE… フィギュア SP/FS・競技別カタログ列挙文字列・NULL 許容）。
--   - judge_label    : 審判識別（J1〜J9 等・審判別素点用・集計のみなら NULL）。
--   - component_type : 項目（フィギュア=TES/PCS/DEDUCTION・体操=D_SCORE/E_SCORE・競技別カタログ列挙文字列・NOT NULL）。
--   - points_scaled  : 当該項目の点数（整数スケール＝×1000・小数は表示で復元・§4.1 と整合）。DEDUCTION は集計時に減算。
--
-- 採番（CLAUDE.md / feedback_flyway_version_sort_after_global_max / feedback_migration_version_collision）:
--   matches CREATE は V76 系・state_model は V85・match_sets は V86・ターン制/添付は V87 系・スコア INT 拡張は V89・
--   tournaments.sport は V91。本 CREATE は origin/main 全体最大 major（V91）の次（V92 系）を採る。
--   V9.* 形式は major=9 として V10〜V91 より前にソートされ from-scratch で死ぬため不可。
--   ※マージ直前に origin/main の最大 Flyway 番号を再確認しリネームすること（並行 PR 衝突回避）。
--
-- 設計書: docs/features/F08.10_match_record_analytics/sports/07_scored.md §4B
--         docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.1.2 / §D.8

CREATE TABLE match_scored_components (
    id              BINARY(16)          NOT NULL              COMMENT 'UUIDv7（原則6）',
    match_id        BINARY(16)          NOT NULL              COMMENT 'matches(id)（同一ドメイン → FK CASCADE）',
    competitor_side ENUM('HOME','AWAY') NULL                  COMMENT '2 者対戦時の side（多人数順位制導入時は NULL・score_entry_id を使う）',
    score_entry_id  BINARY(16)          NULL                  COMMENT '多人数順位制（§5B・後段 Phase）のエントリ参照（2 者対戦時は NULL）',
    apparatus       VARCHAR(32)         NULL                  COMMENT '種目/セグメント（体操 FLOOR… フィギュア SP/FS・競技別カタログ列挙・NULL 許容）',
    judge_label     VARCHAR(32)         NULL                  COMMENT '審判識別（J1〜J9 等・審判別素点用・集計のみなら NULL）',
    component_type  VARCHAR(32)         NOT NULL              COMMENT '項目（フィギュア=TES/PCS/DEDUCTION・体操=D_SCORE/E_SCORE・競技別カタログ列挙）',
    points_scaled   INT                 NOT NULL DEFAULT 0    COMMENT '当該項目の点数（整数スケール×1000・小数は表示で復元・§4.1）。DEDUCTION は集計時に減算',
    created_at      DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_scored_components_match (match_id, competitor_side, apparatus),
    KEY idx_scored_components_entry (score_entry_id),
    CONSTRAINT fk_scored_components_match FOREIGN KEY (match_id)
        REFERENCES matches (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.10/07_scored §4B 採点内訳子表（フィギュア/体操・テナント分離は親 matches）';
