-- F02.10 Phase 1 基盤: 既存ユーザー初回導出ジョブの冪等フラグテーブル
--
-- 設計書 docs/features/F02.10_weather_widget.md §4.4 準拠。
--
-- 主キー方針: シングルトン例外（§4.0 / CLAUDE.md 原則 6 のシングルトン例外条項）。
--   * id TINYINT UNSIGNED + CHECK (id = 1) で行数 1 を強制
--
-- 初期行を INSERT IGNORE で投入し、ジョブ完了時に completed_at を UPDATE する設計。

CREATE TABLE weather_location_bootstrap_jobs (
    id TINYINT UNSIGNED NOT NULL COMMENT '固定値 1（シングルトン制約）',
    completed_at DATETIME DEFAULT NULL COMMENT 'ジョブ完了日時。NULL のときは未完了',
    processed_user_count INT UNSIGNED DEFAULT NULL COMMENT '処理済みユーザー数',
    skipped_user_count INT UNSIGNED DEFAULT NULL COMMENT 'スキップユーザー数（郵便番号未ヒット等）',

    PRIMARY KEY (id),
    CONSTRAINT chk_weather_bootstrap_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='既存ユーザー初回導出ジョブの冪等フラグ（シングルトン）';

-- 初期行を作成（ジョブ未完了の状態）。冪等。
INSERT IGNORE INTO weather_location_bootstrap_jobs (id) VALUES (1);
