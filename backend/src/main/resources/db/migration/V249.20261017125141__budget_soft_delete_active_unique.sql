-- ⚠ 一意制約の変更: 削除済み行のID・名称・親IDを保持したまま同名再作成を許可する。
-- 元のキー列と親NULLの扱いは変えず、生存行だけ1となる生成列を一意キーへ加える。
ALTER TABLE budget_fiscal_years
    ADD COLUMN active_unique_key TINYINT
        GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) VIRTUAL,
    DROP INDEX uq_bfy_scope_name,
    ADD CONSTRAINT uq_bfy_scope_name_active
        UNIQUE (scope_type, scope_id, name, active_unique_key);

ALTER TABLE budget_categories
    ADD COLUMN active_unique_key TINYINT
        GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) VIRTUAL,
    DROP INDEX uq_bc_name,
    ADD CONSTRAINT uq_bc_name_active
        UNIQUE (fiscal_year_id, parent_id, name, active_unique_key);