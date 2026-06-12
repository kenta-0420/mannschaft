-- F08.7 順位UI Wave0: 大会 visibility を 2 値（PUBLIC / MEMBERS_ONLY）から 6 値へ拡張する。
--
-- 6 値（F00 正準 StandardVisibility と同名 5 + 大会専用軸 PARTICIPANTS_ONLY）:
--   PUBLIC / SUPPORTERS_AND_ABOVE / MEMBERS_AND_ABOVE / ADMINS_AND_ABOVE
--   / SCOPE_AFFILIATED / PARTICIPANTS_ONLY
--
-- 既存データ移行（[[feedback_flyway_existing_data_check_drop]] 番人観点）:
--   旧 MEMBERS_ONLY 行は「主催組織に直接所属する全員」相当のため SCOPE_AFFILIATED へ移行する
--   （StandardVisibility doc の「旧 MEMBERS_ONLY 相当の正準値」）。
--
-- ENUM 制約変更の順序（旧値で UPDATE できるよう、まず新旧両対応の広い ENUM へ拡張）:
--   1) ENUM に新 6 値 + 旧 MEMBERS_ONLY を含む過渡的な集合へ MODIFY（旧値はまだ残す）
--   2) 旧 MEMBERS_ONLY 行を SCOPE_AFFILIATED へ UPDATE
--   3) ENUM を最終 6 値へ MODIFY し、DEFAULT を PUBLIC に確定（旧 MEMBERS_ONLY を物理的に削除）
--
-- 採番: tournaments は V8.038 で作成済み。本マイグレーションは全体最大 major（origin/main は V81 系まで）
--       の次として V82.001 を採番する（[[feedback_flyway_version_sort_after_global_max]] 準拠）。
--       V82 は V8.038 含む全先行マイグレーションより後にソートされるため、from-scratch でも
--       tournaments テーブル生成（V8.038）後に本 ALTER が走る正しい順序が保証される。

-- 1) 過渡的 ENUM（新 6 値 + 旧 MEMBERS_ONLY）。NOT NULL は維持、DEFAULT は最終確定まで据え置き。
ALTER TABLE tournaments
    MODIFY COLUMN visibility
        ENUM('PUBLIC','SUPPORTERS_AND_ABOVE','MEMBERS_AND_ABOVE','ADMINS_AND_ABOVE',
             'SCOPE_AFFILIATED','PARTICIPANTS_ONLY','MEMBERS_ONLY')
        NOT NULL DEFAULT 'MEMBERS_ONLY';

-- 2) 既存 MEMBERS_ONLY 行を SCOPE_AFFILIATED（旧 MEMBERS_ONLY 相当の正準値）へ移行。
UPDATE tournaments
    SET visibility = 'SCOPE_AFFILIATED'
    WHERE visibility = 'MEMBERS_ONLY';

-- 3) 最終 6 値へ確定。旧 MEMBERS_ONLY を ENUM から削除し、DEFAULT を PUBLIC に変更。
ALTER TABLE tournaments
    MODIFY COLUMN visibility
        ENUM('PUBLIC','SUPPORTERS_AND_ABOVE','MEMBERS_AND_ABOVE','ADMINS_AND_ABOVE',
             'SCOPE_AFFILIATED','PARTICIPANTS_ONLY')
        NOT NULL DEFAULT 'PUBLIC';
