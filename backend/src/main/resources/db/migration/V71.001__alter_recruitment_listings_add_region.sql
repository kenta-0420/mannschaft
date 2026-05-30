-- F22.1 市（Market）: recruitment_listings に地域列を追加（01_data_model §2）
-- 市ビューの結合キー。既存 location VARCHAR(200)（自由入力・表示用）は残し、
-- 構造化フィルタ用にコード列を追加する。
--
-- マスタは新規作成しない。既存 prefectures(code CHAR(2)) / cities(code CHAR(5)) を
-- 参照する（FK なし・整合性は Service 層で検証。CLAUDE.md 原則 1）。
-- 既存行は両列 NULL（後方互換・バックフィル不要）。

ALTER TABLE recruitment_listings
    ADD COLUMN prefecture_code CHAR(2) NULL COMMENT '都道府県コード（JIS X 0401）。prefectures.code 参照（FKなし）' AFTER location,
    ADD COLUMN city_code       CHAR(5) NULL COMMENT '市区町村コード（JIS X 0402）。cities.code 参照（FKなし）'       AFTER prefecture_code;

-- 市ビュー（地域×ジャンル×状態）の検索用インデックス。
-- 既存の category 検索インデックス（idx_rl_category_search）と併用する。
CREATE INDEX idx_rl_market_region
    ON recruitment_listings (prefecture_code, city_code, category_id, status);
