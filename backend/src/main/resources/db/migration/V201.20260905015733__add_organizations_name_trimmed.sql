-- CMP-260901-1538 柱③-A 検分第4巡是正（P1-2）: 同名確認フローの候補検索
-- （OrganizationRepository#findActiveByNormalizedName(ForUpdate)）が
-- `TRIM(name) = TRIM(?) COLLATE utf8mb4_0900_ai_ci` で索引の効かない全表走査
-- （FOR UPDATE 版は全表ロックにもなり得る）になっていたのを是正する。
--
-- 生成列 name_trimmed（GENERATED ALWAYS AS (TRIM(name)) STORED）を追加し、
-- インデックスを張ることで `name_trimmed = TRIM(?)` の等価比較が索引を使えるようにする。
-- STORED にする理由: FOR UPDATE のロッキングリードでインデックスレンジロックの対象に
-- するため、VIRTUAL（都度計算）ではなく実体を持つ STORED 生成列にする。
-- 照合順序は name 列（utf8mb4_0900_ai_ci、V?__unify_table_collation.sql で統一済み）を
-- TRIM() で引き継ぐため明示指定は不要。
ALTER TABLE organizations
    ADD COLUMN name_trimmed VARCHAR(100)
        GENERATED ALWAYS AS (TRIM(name)) STORED
        COMMENT '柱③-A 同名確認フロー用: TRIM(name)の生成列（索引対象）',
    ADD KEY idx_organizations_name_trimmed (name_trimmed);
