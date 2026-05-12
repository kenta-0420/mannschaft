-- F02.10 Phase 1 基盤: ユーザー地点キャッシュテーブル
--
-- 設計書 docs/features/F02.10_weather_widget.md §4.2 準拠。
--
-- 主キー方針: CLAUDE.md 原則 6 を適用し UuidV7Entity を継承する想定。
--   * id BINARY(16)（UUIDv7）
--   * latitude_rounded / longitude_rounded は 0.5 度丸めの値（プライバシー保護）
--
-- FK 方針: クロスドメイン FK 禁止原則に従い、user_id への FK は張らない。
--   * CLAUDE.md「クロスドメイン FK は作らない」原則に統一
--   * 参照整合性はアプリケーション層（UserAnonymizedEvent リスナー）で保証
--   * INDEX のみで検索性能を担保
--
-- 暗号化された郵便番号との照合用に postal_code_hash（HMAC-SHA256）を保持する。

CREATE TABLE user_weather_locations (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7（UuidV7Entity 継承）',
    user_id BIGINT UNSIGNED NOT NULL COMMENT 'auth ドメインの users.id（FK は張らない・原則準拠）',
    label VARCHAR(50) NOT NULL DEFAULT 'home' COMMENT '地点ラベル（将来複数地点拡張用、本機能では home 固定）',
    country_code CHAR(2) NOT NULL COMMENT 'users.country_code のスナップショット',
    postal_code_hash CHAR(64) NOT NULL COMMENT '平文郵便番号の HMAC-SHA256（APP_HMAC_SECRET 使用）',
    latitude_rounded DECIMAL(4,1) NOT NULL COMMENT '0.5 度丸めの緯度（キャッシュキー兼）',
    longitude_rounded DECIMAL(4,1) NOT NULL COMMENT '0.5 度丸めの経度',
    place_name_snapshot VARCHAR(180) NOT NULL COMMENT 'UI 表示用の地名スナップショット',
    derived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '導出日時',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    -- 将来の複数地点拡張に備え、最初から (user_id, label) 複合キー
    UNIQUE KEY uq_uwl_user_label (user_id, label),

    -- 集計・キャッシュ統計用（同一エリアのユーザー数把握）
    INDEX idx_uwl_grid (latitude_rounded, longitude_rounded),

    -- ユーザー削除イベント時の検索用
    INDEX idx_uwl_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='ユーザー地点キャッシュ（F02.10 天気ウィジェット・0.5度丸め緯度経度）';
