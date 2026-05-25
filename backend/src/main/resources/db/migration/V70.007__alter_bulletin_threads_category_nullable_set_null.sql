-- F05.1 掲示板: カテゴリ削除によるスレッド全消失バグの根治
--
-- 背景:
--   設計書 F05.1 §3 は bulletin_threads.category_id を NULL 許容 + FK ON DELETE SET NULL、
--   §5 は「カテゴリ削除時に配下スレッドを未分類(category_id=NULL)へ退避し、スレッドは残す」と定める。
--   しかし V5.002 の実装は category_id BIGINT UNSIGNED NOT NULL + FK ON DELETE CASCADE であり、
--   カテゴリを物理削除すると配下スレッドが全件巻き添え削除される（データ消失級バグ）。
--   加えて自動生成スレッド（SAFETY_CHECK / SURVEY 連携）は category_id=NULL を前提とするのに
--   NOT NULL 制約と矛盾していた。
--
-- 対応:
--   1. category_id を NULL 許容化（未分類 = NULL）
--   2. 既存 FK fk_bulletin_threads_category を DROP
--   3. 同名 FK を ON DELETE SET NULL で再作成（カテゴリ物理削除時もスレッドは未分類として残る）
--
-- 注意: MODIFY COLUMN → DROP FOREIGN KEY → ADD CONSTRAINT の順で実行する。

-- 1. category_id を NULL 許容化
ALTER TABLE bulletin_threads
    MODIFY COLUMN category_id BIGINT UNSIGNED NULL;

-- 2. 既存 FK（ON DELETE CASCADE）を削除
ALTER TABLE bulletin_threads
    DROP FOREIGN KEY fk_bulletin_threads_category;

-- 3. FK を ON DELETE SET NULL で再作成
ALTER TABLE bulletin_threads
    ADD CONSTRAINT fk_bulletin_threads_category
        FOREIGN KEY (category_id) REFERENCES bulletin_categories(id) ON DELETE SET NULL;
