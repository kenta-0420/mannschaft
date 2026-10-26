-- F08.7 順位UI 項目③: スコア入力の編集権限細分化。
--
-- 「ORG 管理者 OR 当該大会の指名スコアキーパー OR その試合の参加チーム ADMIN」がスコア入力可とするため、
-- 大会単位のスコアキーパー指名テーブルを新設する。スコアキーパーは新ロールではなく「指名された user_id」
-- で表現する（F08.10 MatchAccessService の scorekeeper_user_id 方式に倣う）。指名管理は主催組織 ADMIN が行う。
--
-- 設計書: docs/features/F08.7_standings_ui（項目③ スコア入力編集権限の細分化）
--
-- 採番: 全体最大 major（origin/main は V82 系まで）の次として V83.001 を採番する
--       （[[feedback_flyway_version_sort_after_global_max]] 準拠）。新規テーブルゆえ既存データは無く、
--       from-scratch 番人テスト（FlywayFromScratchMigrationTest）で足りる。
--
-- 原則準拠:
--   - 主キーは UUIDv7（原則6・UuidV7Entity 継承）。id は BINARY(16)。
--   - クロスドメイン FK は張らない（原則1）。tournament_id / user_id / created_by は ID 値のみ保持し、
--     参照整合性はアプリ層で保証する。
--   - 指名は「現在の権限状態」であり履歴ではないため、解除は物理削除で行う（deleted_at を持たない）。

CREATE TABLE tournament_scorekeepers (
    id            BINARY(16) NOT NULL COMMENT 'UUIDv7（原則6）',
    tournament_id BIGINT     NOT NULL COMMENT '対象大会（tournaments.id・FK なし／原則1）',
    user_id       BIGINT     NOT NULL COMMENT 'スコアキーパーに指名されたユーザー（users.id・FK なし／原則1）',
    created_by    BIGINT     NOT NULL COMMENT '指名した主催組織 ADMIN の user_id（退会時も履歴保持）',
    created_at    DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_tournament_scorekeepers_tournament (tournament_id),
    -- 同一大会で同一ユーザーの二重指名を防ぐ
    UNIQUE KEY uq_tournament_scorekeeper (tournament_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.7 大会スコアキーパー指名（項目③ スコア入力編集権限の細分化）';
