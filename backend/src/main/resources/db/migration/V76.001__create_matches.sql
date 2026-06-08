-- F08.10 / 01 §B.1: matches（全種別試合の単一の真実）。
--
-- 練習(PRACTICE)/親善(FRIENDLY)/大会(TOURNAMENT)/リーグ(LEAGUE) の全種別を 1 テーブルで保持する。
-- スコアは matches が正本（home_score/away_score・PK 戦は home/away_penalty_score で本戦と分離）。
--
-- 原則準拠（CLAUDE.md・01 §A.3/§B）:
--   - 新規テーブルゆえ主キーは UUIDv7（原則6・UuidV7Entity 継承）。id は BINARY(16)。
--   - organization_id/team_id/opponent_team_id/scorekeeper_user_id/created_by は
--     クロスドメインへの ID 参照（原則1・FK なし）。
--   - tournament_fixture_id / schedule_id は既存 BIGINT テーブルへの ID 参照ゆえ BIGINT 据え置き
--     （原則6「既存テーブルの BIGINT ID は変更しない」・01 §B.1 fixture が BIGINT である理由）。
--   - テナント絞り込みは AbstractTenantAwareRepository（原則7）・論理削除は deleted_at（原則3）。
--
-- 設計書: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.1
-- ※採番はマージ直前に origin/main の最大 Flyway 番号を再確認しリネームすること（並行 PR 衝突回避）。

CREATE TABLE matches (
    id                   BINARY(16)            NOT NULL                  COMMENT 'UUIDv7（UuidV7Entity 継承・原則6）',
    organization_id      BIGINT                NOT NULL                  COMMENT 'テナント（organization ドメインへの ID 参照・原則1/7・FK なし）',
    team_id              BIGINT                NOT NULL                  COMMENT '記録/ホーム主体チーム（team ドメイン ID 参照・FK なし）',
    sport                VARCHAR(32)           NOT NULL DEFAULT 'SOCCER' COMMENT '競技種別（多競技対応・enum 文字列）',
    kind                 VARCHAR(16)           NOT NULL                  COMMENT 'PRACTICE/FRIENDLY/TOURNAMENT/LEAGUE（enum 文字列）',
    tournament_fixture_id BIGINT               NULL                      COMMENT '大会 fixture リンク（tournament ドメインへの BIGINT ID 参照・NULL=単独試合・FK なし）',
    schedule_id          BIGINT                NULL                      COMMENT 'カレンダー連携（F03.1・schedules への BIGINT ID 参照・FK なし）',
    home_away            ENUM('HOME','AWAY','NEUTRAL') NOT NULL DEFAULT 'HOME' COMMENT '主体チームのホーム/アウェイ/中立地',
    opponent_team_id     BIGINT                NULL                      COMMENT '登録相手チーム（team ドメイン ID 参照・NULL 可・FK なし）',
    opponent_name        VARCHAR(128)          NULL                      COMMENT '未登録相手名（opponent_team_id が NULL のとき使用）',
    kickoff_at           DATETIME              NULL                      COMMENT 'キックオフ日時（予定/実績）',
    venue                VARCHAR(200)          NULL                      COMMENT '会場',
    duration_minutes     SMALLINT UNSIGNED     NULL                      COMMENT '試合長（分・前後半90＋延長の試合通算・出場時間 out 既定値に使用）',
    period_format        VARCHAR(32)           NULL                      COMMENT '試合形式（HALVES_45/QUARTERS_10 等・PeriodType と対応）',
    home_score           SMALLINT UNSIGNED     NULL                      COMMENT 'ホーム本戦スコア（正本・延長得点も合算）',
    away_score           SMALLINT UNSIGNED     NULL                      COMMENT 'アウェイ本戦スコア（正本・延長得点も合算）',
    home_penalty_score   SMALLINT UNSIGNED     NULL                      COMMENT 'ホーム PK 戦スコア（本戦と分離）',
    away_penalty_score   SMALLINT UNSIGNED     NULL                      COMMENT 'アウェイ PK 戦スコア（本戦と分離）',
    status               VARCHAR(16)           NOT NULL DEFAULT 'SCHEDULED' COMMENT 'SCHEDULED/IN_PROGRESS/COMPLETED/POSTPONED/CANCELLED',
    scorekeeper_user_id  BIGINT                NULL                      COMMENT '記録係ユーザー（公式戦・user ドメイン ID 参照・FK なし）',
    has_scorekeeper      BOOLEAN               NOT NULL DEFAULT FALSE    COMMENT '記録モード判定（TRUE=公式戦/FALSE=共同記録）',
    notes                TEXT                  NULL                      COMMENT '備考',
    created_by           BIGINT                NOT NULL                  COMMENT '作成者（user ドメイン ID 参照・FK なし）',
    version              BIGINT                NOT NULL DEFAULT 0         COMMENT '楽観ロック（@Version・メタ更新専用）',
    created_at           DATETIME              NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME              NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at           DATETIME              NULL                      COMMENT '論理削除（原則3）',

    PRIMARY KEY (id),
    KEY idx_matches_org (organization_id, deleted_at),
    KEY idx_matches_team (team_id, kickoff_at),
    KEY idx_matches_fixture (tournament_fixture_id),
    KEY idx_matches_schedule (schedule_id),
    KEY idx_matches_kind (organization_id, kind, kickoff_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.10/01 全種別試合の単一レコード（UUIDv7・テナントスコープ・論理削除）';
