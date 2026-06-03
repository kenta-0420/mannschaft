-- F08.7.1 / 05 試合メンバー表（項目拡充）: tournament_match_rosters への列追加。
--
--   1. registration_number  協会選手登録番号（背番号 jersey_number とは別の恒久番号。NULL 可）。
--   2. uniform_set_id        着用 team_uniform_set への ID 参照（試合ごとのカラー衝突回避で上書き可・NULL 可）。
--
-- tournament_match_rosters は既存 BIGINT PK テーブル（TournamentMatchRosterEntity = IDENTITY Long）ゆえ
-- ID 方式は変更しない。列追加自体は PK 型に依存せず可能。
--
-- uniform_set_id は team ドメインの team_uniform_set への参照（roster は tournament ドメイン）になるため、
-- クロスドメイン FK は張らず ID 参照のみとする（原則1）。指定セットが自チームのものかはアプリ層で検証する。
--
-- 設計書: docs/features/F08.7.1_tournament_extensions/05_match_roster.md §8.1 / §8.2 / §8.6

ALTER TABLE tournament_match_rosters
    ADD COLUMN registration_number VARCHAR(32) NULL
        COMMENT '協会選手登録番号（背番号とは別・NULL 可）',
    ADD COLUMN uniform_set_id BINARY(16) NULL
        COMMENT '着用 team_uniform_set への ID 参照（team ドメイン・クロスドメイン FK なし／原則1・NULL 可）';
