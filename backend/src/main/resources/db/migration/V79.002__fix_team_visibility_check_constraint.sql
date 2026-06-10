-- teams.visibility の CHECK 制約を新しい ENUM 値に合わせて更新する
-- V79.001 で ENUM 型は変更済みだが CHECK 制約が古い値（ORGANIZATION_ONLY/PRIVATE）のまま残っているため修正する

ALTER TABLE teams DROP CHECK chk_teams_visibility;

ALTER TABLE teams
    ADD CONSTRAINT chk_teams_visibility
        CHECK (visibility IN ('PUBLIC', 'GUESTS_AND_ABOVE', 'SUPPORTERS_AND_ABOVE', 'MEMBERS_AND_ABOVE'));
