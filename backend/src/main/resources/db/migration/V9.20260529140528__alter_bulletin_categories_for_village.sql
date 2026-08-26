-- F17.1 村掲示板グローバル方式: bulletin_categories へ村スコープ列を追加
-- V9.133__alter_bulletin_for_village.sql（bulletin_threads 側）と同形。
-- scope_village_id への FK は張らない（クロスドメイン参照・原則1）。
--
-- scope_id（VARCHAR ではなく BIGINT UNSIGNED）の直後に配置する。
-- 元 DDL: V5.001__create_bulletin_categories_table.sql（scope_type → scope_id → name の並び）。

ALTER TABLE bulletin_categories
    ADD COLUMN scope_village_id BINARY(16) NULL COMMENT 'scope_type=VILLAGE のとき村ID（FK 張らない / 原則1）' AFTER scope_id,
    ADD KEY idx_bc_scope_village (scope_village_id);
