-- F02.10 Phase 1 基盤: GeoNames 取り込みメタデータテーブル
--
-- 設計書 docs/features/F02.10_weather_widget.md §4.3 準拠。
--
-- 主キー方針: シングルトン例外（§4.0 / CLAUDE.md 原則 6 のシングルトン例外条項）。
--   * id TINYINT UNSIGNED + CHECK (id = 1) で行数 1 を強制
--   * UUIDv7 化の利点がゼロ
--
-- 初期 INSERT は実行しない（GeoNames インポートバッチが初回 INSERT する）。

CREATE TABLE geonames_metadata (
    id TINYINT UNSIGNED NOT NULL COMMENT '固定値 1（シングルトン制約）',
    last_imported_at DATETIME NOT NULL COMMENT '最終取り込み日時',
    source_version VARCHAR(50) NOT NULL COMMENT 'GeoNames のダウンロードファイル更新日（例: allCountries-20260501）',
    imported_row_count BIGINT UNSIGNED NOT NULL COMMENT '取り込み行数',
    imported_by_user_id BIGINT UNSIGNED DEFAULT NULL COMMENT '手動実行ユーザー（cron 自動実行は NULL）',

    PRIMARY KEY (id),
    CONSTRAINT chk_geonames_metadata_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='GeoNames 取り込みのバージョン管理（シングルトン）';
