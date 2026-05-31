-- F22.1 市（Market）Phase 2 足場C 第一陣: teams に地域コード列を追加（Expand）。
-- 既存の自由入力フィールド prefecture VARCHAR(20) / city VARCHAR(50)（表示用）は残し、
-- 構造化フィルタ・市ビュー結合用にコード列を追加する。
--
-- マスタは新規作成しない。既存 prefectures(code CHAR(2)) / cities(code CHAR(5)) を
-- 参照する（FK なし・整合性は Service 層で検証。CLAUDE.md 原則 1 / V71.001 前例踏襲）。
-- 既存行は両列 NULL（後方互換）。名称→コードのバックフィルは別工程（本陣では実行しない）。

ALTER TABLE teams
    ADD COLUMN prefecture_code CHAR(2) NULL COMMENT '都道府県コード（JIS X 0401）。prefectures.code 参照（FKなし）' AFTER city,
    ADD COLUMN city_code       CHAR(5) NULL COMMENT '市区町村コード（JIS X 0402）。cities.code 参照（FKなし）'       AFTER prefecture_code;

-- 市ビュー（地域フィルタ）用の検索インデックス。
CREATE INDEX idx_teams_region ON teams (prefecture_code, city_code);
