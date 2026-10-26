-- F08.7.1 / 05 試合メンバー表: 提出締切カラムを tournament_matches に追加する。
--
-- 自チーム（チーム代表 ADMIN/DEPUTY）が試合メンバー表を提出する締切を試合単位で設定する。
-- 締切を過ぎた match への提出（PUT rosters/me・apply-template）は 409（締切超過）で拒否する。
-- NULL = 締切なし（いつでも提出可）。
--
-- tournament_matches は既存 BIGINT PK テーブルゆえ ID 方式は変更しない（原則6 は新規テーブルのみ対象）。
--
-- 設計書: docs/features/F08.7.1_tournament_extensions/05_match_roster.md §2

ALTER TABLE tournament_matches
    ADD COLUMN roster_deadline DATETIME NULL
        COMMENT 'メンバー表提出締切（NULL=締切なし）。締切後の自チーム提出は 409 でロック';
