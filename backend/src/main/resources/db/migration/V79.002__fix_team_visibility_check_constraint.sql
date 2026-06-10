-- teams.visibility の CHECK 制約を新しい ENUM 値に合わせて更新する
-- V79.001 の MODIFY COLUMN ENUM(...) で MySQL が CHECK 制約を自動削除した環境と
-- 残存している環境の両方に対応するため IF EXISTS で保護する

ALTER TABLE teams DROP CHECK IF EXISTS chk_teams_visibility;

ALTER TABLE teams
    ADD CONSTRAINT chk_teams_visibility
        CHECK (visibility IN ('PUBLIC', 'GUESTS_AND_ABOVE', 'SUPPORTERS_AND_ABOVE', 'MEMBERS_AND_ABOVE'));
