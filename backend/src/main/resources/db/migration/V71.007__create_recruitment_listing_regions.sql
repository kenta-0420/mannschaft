-- F22.1 Phase2 D 複数地域募集（N:N）: 札の地域中間表（02_api_design §4 / 01_data_model）
-- 1 つの札（listing_id）に対し複数の地域（都道府県 / 市区町村）を N 件紐づける。
-- 「東京＋神奈川」「同一県内の複数市」のような複数地域募集を表現する。
--
-- 依存テーブル: recruitment_listings (V3.119) / 地域列追加 (V71.001)
-- 主キー: BINARY(16)（UUIDv7・UuidV7Entity 継承。CLAUDE.md 原則 6）
-- FK 方針:
--   listing_id → recruitment_listings(id) は同一ドメイン（recruitment）なので CASCADE 可（原則 2）
--   prefecture_code（prefectures）/ city_code（cities）はクロスドメインのため FK なし・index のみ。
--   整合性は Service 層（MarketRegionValidator）で検証する（原則 1）。
-- 旧単一列（recruitment_listings.prefecture_code / city_code）は後方互換のため残置し、
--   代表 1 件（先頭地域）を同期する（Service 層が担当）。
-- 雛形参照: V71.002__create_recruitment_friend_targets.sql

CREATE TABLE recruitment_listing_regions (
    id              BINARY(16)      NOT NULL COMMENT 'PK（UUIDv7）',
    listing_id      BIGINT UNSIGNED NOT NULL COMMENT 'FK -> recruitment_listings.id（同一ドメイン CASCADE）',
    prefecture_code CHAR(2)         NOT NULL COMMENT '都道府県コード（JIS X 0401）。県単位の地域でも必須。prefectures.code 参照（FKなし）',
    city_code       CHAR(5)         NULL     COMMENT '市区町村コード（JIS X 0402）。県単位は NULL。cities.code 参照（FKなし）',
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_rlr_listing
        FOREIGN KEY (listing_id) REFERENCES recruitment_listings (id) ON DELETE CASCADE,
    -- 同一札への同一地域の重複登録防止（県単位は city_code=NULL。NULL は MySQL UNIQUE で複数許容
    -- されるが、同一県の県単位は 1 行のみ・Service の重複排除で担保する）。
    CONSTRAINT uk_rlr_listing_region UNIQUE (listing_id, prefecture_code, city_code),
    -- 市区町村ノード→札の逆引き（市の件数集計・検索 EXISTS）。
    INDEX idx_rlr_city (city_code, listing_id),
    -- 都道府県ノード→札の逆引き（県ロールアップ・件数集計）。
    INDEX idx_rlr_pref (prefecture_code, city_code, listing_id),
    -- 札→地域一覧（レスポンス enrich のバルク取得）。
    INDEX idx_rlr_listing (listing_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F22.1 市: 複数地域募集（N:N）の地域中間表';
