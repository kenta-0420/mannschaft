-- F22.1 Phase2 E: 地域名の多言語訳テーブル（マスタ非破壊・別訳テーブル方式）
--
-- prefectures / cities マスタは日本語名のみを持つ（V8.001 / V12.011・約1,900件）。
-- 本テーブルは「コード × 言語」で訳名を別管理し、マスタ本体には一切手を加えない。
-- 訳が存在しない（コード,言語）の組はアプリ側で日本語名（マスタ name）にフォールバックする。
--
-- 【主キー方針 — CLAUDE.md 原則6 マスタ例外】
--   本テーブルは全テナント共通で参照される静的なマスタ性データであり、
--   行は固定的（テナント/ユーザーごとに増えない）。シャーディング時は全シャードへ
--   同一データをコピーする運用となるため、原則6（新規テーブル UUIDv7）の意図（各ノードで
--   独立発番）に該当しない。よって自然キー (code, lang) の複合主キーを採用する。
--
-- 【FK 方針】
--   prefectures.code(CHAR2) と cities.code(CHAR5) の双方を 1 カラム code に格納するため、
--   どちらか一方への FK は張れない。クロス参照の整合性はアプリ層（Service）で検証する
--   （CLAUDE.md ドメイン境界の原則と整合）。
CREATE TABLE region_translations (
    code VARCHAR(5)  NOT NULL COMMENT '地域コード（都道府県2桁 / 市区町村5桁の双方を格納）',
    lang VARCHAR(5)  NOT NULL COMMENT '言語コード（en/zh/ko/es/de。ja は元マスタ name が正なので格納しない）',
    name VARCHAR(40) NOT NULL COMMENT '当該言語での地域表示名',
    PRIMARY KEY (code, lang),
    INDEX idx_region_translations_lang (lang)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='地域名の多言語訳（マスタ非破壊・自然キー = CLAUDE.md 原則6 マスタ例外）';
