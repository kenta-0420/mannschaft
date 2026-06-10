-- teams.visibility の CHECK 制約を新しい ENUM 値で再追加する
-- V79.001 の MODIFY COLUMN ENUM(...) 実行時に MySQL 8.0 が CHECK 制約を自動削除することが
-- from-scratch テストで確認されたため、DROP は不要。ADD CONSTRAINT のみ実行する。

ALTER TABLE teams
    ADD CONSTRAINT chk_teams_visibility
        CHECK (visibility IN ('PUBLIC', 'GUESTS_AND_ABOVE', 'SUPPORTERS_AND_ABOVE', 'MEMBERS_AND_ABOVE'));
