-- F05.5 ファイル共有セキュリティ強化 B: 最低可視ロール（表示制御）
--
-- shared_folders / shared_files に min_visible_role 列を追加する。
--   - NULL（既定）= 所属者全員可視（SCOPE_AFFILIATED＝従来挙動・非回帰）。
--   - 値は enum FileVisibilityRole（SUPPORTERS_AND_ABOVE / MEMBERS_AND_ABOVE / ADMINS_AND_ABOVE）を STRING 保存。
--   - TEAM / ORGANIZATION スコープでのみ意味を持つ（PERSONAL は所有者のみ・大会は主催組織 ORG ロールで判定）。
--   - 継承規約: ファイル値 NULL ならフォルダ値を継承、フォルダも NULL なら判定スキップ。
--
-- 原則準拠: 列追加のみ・NULL 許容ゆえ既存データ（列 NULL）は従来どおり所属者全員可視のまま（AC-B6 非回帰）。
-- クロスドメイン FK なし（原則1）。from-scratch でも適用済み環境でも壊れない（NULL 許容の純追加）。

ALTER TABLE shared_folders
    ADD COLUMN min_visible_role VARCHAR(24) NULL
        COMMENT 'B: 最低可視ロール（NULL=所属者全員可視。SUPPORTERS_AND_ABOVE/MEMBERS_AND_ABOVE/ADMINS_AND_ABOVE）'
        AFTER created_by;

ALTER TABLE shared_files
    ADD COLUMN min_visible_role VARCHAR(24) NULL
        COMMENT 'B: ファイル個別の最低可視ロール（NULL=フォルダ継承）'
        AFTER created_by;
