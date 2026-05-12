-- F02.10 Phase 1 基盤: GeoNames 郵便番号→緯度経度マスタテーブル
--
-- 設計書 docs/features/F02.10_weather_widget.md §4.1 準拠。
--
-- 主キー方針: マスタ例外（§4.0 / CLAUDE.md 原則 6 のマスタ例外条項）。
--   * 全テナント共通の参照データ（CC BY 4.0 の GeoNames 公開データ）
--   * 引き当ては (country_code, postal_code) で行うため自然キーが最も効率的
--   * 1.5M 行で UUIDv7 化は単純オーバーヘッドのみで利点ゼロ
--
-- 運用:
--   * バッチで INSERT ... ON DUPLICATE KEY UPDATE 方式の upsert
--   * 削除済み郵便番号は残置（参照され続ける可能性のため）
--
-- ※ V62-V65 は既存の Phase 1a/2a/3a/4b マイグレーションで埋まっており、
--   V66.001 は F09.15/16 S0 (reference_id_uuid) で使用済みのため V66.002〜V66.005 を割当。

CREATE TABLE postal_codes (
    country_code CHAR(2) NOT NULL COMMENT 'ISO 3166-1 alpha-2',
    postal_code VARCHAR(20) NOT NULL COMMENT '国別フォーマット（JP は半角ハイフン除去後の 7 桁）',
    place_name VARCHAR(180) NOT NULL COMMENT '地名（表示用、GeoNames の生値）',
    admin1_name VARCHAR(100) DEFAULT NULL COMMENT '第1行政区画（都道府県・州）',
    admin2_name VARCHAR(100) DEFAULT NULL COMMENT '第2行政区画（市区町村）',
    latitude DECIMAL(8,5) NOT NULL COMMENT '緯度（生値、丸め前）',
    longitude DECIMAL(8,5) NOT NULL COMMENT '経度（生値、丸め前）',
    accuracy TINYINT UNSIGNED DEFAULT NULL COMMENT 'GeoNames の精度コード（1-6）',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- 自然複合キー（引き当てキー）
    PRIMARY KEY (country_code, postal_code),

    -- 行政区画別の集計用（将来）
    INDEX idx_pc_country_admin1 (country_code, admin1_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='GeoNames 郵便番号→緯度経度マスタ（F02.10 天気ウィジェット）';
