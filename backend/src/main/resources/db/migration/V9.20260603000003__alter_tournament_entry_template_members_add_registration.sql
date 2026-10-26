-- F08.7.1 / 05 試合メンバー表（項目拡充）: エントリーテンプレメンバーへ協会選手登録番号を追加する。
--
-- テンプレ適用（apply-template）時に
--   tournament_entry_template_members.registration_number → tournament_match_rosters.registration_number
-- へ複製される。
--
-- tournament_entry_template_members は既存 UUIDv7 PK テーブル（CHAR(36)・V9.124）。
-- 列追加自体は PK 型に依存せず可能。
--
-- 設計書: docs/features/F08.7.1_tournament_extensions/05_match_roster.md §8.1

ALTER TABLE tournament_entry_template_members
    ADD COLUMN registration_number VARCHAR(32) NULL
        COMMENT '協会選手登録番号（背番号とは別・NULL 可）。テンプレ適用時に roster へ複製';
