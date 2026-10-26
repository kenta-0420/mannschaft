-- F08.10 / sports/07_scored.md §5B / 01 §B.1.2 / §D.8: match_score_entries
-- （採点競技＝フィギュアスケート/体操の多人数順位制の出場者エントリ子表）。
--
-- 採点競技の MVP は 2 者対戦（matches.home_score/away_score・整数スケール×1000）だが、本来形は
-- 「多人数が同一種目に出場し合計点で順位を競う大会」（フィギュア大会・体操の個人総合順位）。
-- 1 match=1 種目（イベント）として複数の出場者（エントリ）が合計点を持ち、合計点降順で順位を導出する。
-- これは home/away の 2 者モデルを超える後段 Phase の新経路（§5B）。
--
-- 二層正本（再導出パターン・§5B.2 / §4B.2 / §B.1.2）:
--   正本は本表（total_scaled・順位 rank_position）。整合策として matches.home_score に「優勝エントリ
--   or 自チーム最上位エントリの合計点」を補助的に格納し、順位表/ダッシュボードの既存導線が空にならないようにする
--   （match_sets〔セット内得点→獲得セット数〕・団体戦〔子ボード勝ち星→親列〕と同じ二層正本構造）。
--
-- 原則準拠（CLAUDE.md・01 §A.4 / §B.6 / sports/07_scored.md §5B.1）:
--   - 新規テーブルゆえ主キーは UUIDv7（原則6）。id は BINARY(16)。
--   - organization_id / deleted_at は【持たない】。テナント分離は親 matches で担保し、子は match_id
--     スコープでのみアクセスする二段アクセス（01 §A.4・IDOR 根絶）。子の削除は親 matches の CASCADE に従う。
--   - match_id → matches(id) は同一 match ドメイン内ゆえ FK＋ON DELETE CASCADE 可（原則2）。
--   - competitor_user_id / competitor_team_id は user / team ドメインの ID 参照ゆえ【FK を張らない】
--     （クロスドメイン FK 禁止・原則1）。整合性はアプリ層で保証し、列はインデックスのみ。
--
-- 列の使い分け（sports/07_scored.md §5B.1）:
--   - competitor_user_id : 出場選手（user ドメイン ID・未登録は NULL）。
--   - competitor_name    : 未登録選手名（competitor_user_id NULL のときの表示名・NULL 許容）。
--   - competitor_team_id : 所属チーム（team ドメイン ID・団体採点時・NULL 許容）。
--   - total_scaled       : 合計点（整数スケール×1000・§4.1 と整合・内訳〔§4B〕の集計 or 直接入力）。
--   - rank_position      : 順位（合計点の降順で Service が導出・同点同順位〔1,2,2,4〕・再計算で更新）。
--
-- 採番（CLAUDE.md / feedback_flyway_version_sort_after_global_max / feedback_migration_version_collision）:
--   matches CREATE は V76 系・state_model は V85・match_sets は V86・ターン制/添付は V87 系・スコア INT 拡張は V89・
--   tournaments.sport は V91・採点内訳子表 match_scored_components は V92。本 CREATE は origin/main 全体
--   最大 major（V92）の次（V93 系）を採る。V9.* 形式は major=9 として V10〜V92 より前にソートされ
--   from-scratch で死ぬため不可。
--   ※マージ直前に origin/main の最大 Flyway 番号を再確認しリネームすること（並行 PR 衝突回避）。
--
-- 関連: match_scored_components.score_entry_id（V92.001 で用意済）は本表のエントリを参照する列
--       （多人数順位制併用時に内訳をエントリ単位で束ねる）。match_scored_components 側の FK は
--       設計どおり張らない（NULL 許容の論理参照・§4B.1）。
--
-- 設計書: docs/features/F08.10_match_record_analytics/sports/07_scored.md §5B
--         docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.1.2 / §D.8

CREATE TABLE match_score_entries (
    id                  BINARY(16)        NOT NULL              COMMENT 'UUIDv7（原則6）',
    match_id            BINARY(16)        NOT NULL              COMMENT 'matches(id)（同一ドメイン → FK CASCADE）',
    competitor_user_id  BIGINT            NULL                  COMMENT '出場選手（user ドメイン ID 参照・未登録は NULL・原則1）',
    competitor_name     VARCHAR(128)      NULL                  COMMENT '未登録選手名（competitor_user_id NULL のときの表示名）',
    competitor_team_id  BIGINT            NULL                  COMMENT '所属チーム（team ドメイン ID 参照・団体採点時・原則1）',
    total_scaled        INT               NOT NULL DEFAULT 0    COMMENT '合計点（整数スケール×1000・§4.1）',
    rank_position       SMALLINT UNSIGNED NULL                  COMMENT '順位（合計点降順で導出・同点同順位・Service が再計算）',
    created_at          DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_score_entries_match (match_id, rank_position),
    KEY idx_score_entries_user (competitor_user_id),
    CONSTRAINT fk_score_entries_match FOREIGN KEY (match_id)
        REFERENCES matches (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.10/07_scored §5B 多人数順位制の出場者エントリ子表（フィギュア/体操・テナント分離は親 matches）';
