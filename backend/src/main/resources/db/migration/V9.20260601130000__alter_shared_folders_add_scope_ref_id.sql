-- F08.7.1 / 04 リーグ単位ファイル置き場: shared_folders に scope_ref_id 列を 1 列追加する。
--
-- 既存 F05.5 ファイル共有（shared_folders）を再利用し、大会・ディビジョン単位のフォルダを新設する。
-- scope_type に新値 TOURNAMENT / TOURNAMENT_DIVISION を追加（VARCHAR(20) ゆえ DDL 変更不要・enum 値の追加のみ）。
-- 唯一の DDL 変更が本 scope_ref_id（大会 ID / ディビジョン ID 保持）である。
--
-- 設計書: docs/features/F08.7.1_tournament_extensions/04_file_storage.md §2.1
--
-- 原則準拠:
--   - クロスドメイン FK は張らない（原則1）。scope_ref_id は tournaments.id / tournament_divisions.id の
--     ID 値のみ保持し、参照整合性はアプリ層で保証する。
--   - 既存スコープ（TEAM/ORGANIZATION/PERSONAL）では NULL。
--   - クォータ計量は主催組織に集約するため StorageScopeType は据え置き（§6）。新値は追加しない。
ALTER TABLE shared_folders
    ADD COLUMN scope_ref_id BIGINT UNSIGNED NULL
        COMMENT 'F08.7.1: 大会 ID / ディビジョン ID（TOURNAMENT(_DIVISION) スコープのみ。FK なし・原則1）'
        AFTER user_id;

-- 大会/ディビジョン別フォルダ一覧の逆引き（organization_id=主催組織で絞った上での scope 解決）。
CREATE INDEX idx_shared_folders_tournament
    ON shared_folders (organization_id, scope_type, scope_ref_id, parent_id, name);
