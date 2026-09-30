-- 組織へのチーム加盟申請の受付・チームグループ機能の設定列（F01.2.1 §5.5）
ALTER TABLE organizations
    ADD COLUMN team_application_enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'チームからの加盟申請を受け付けるか（既定 off）',
    ADD COLUMN team_groups_enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'チームグループ機能を使うか',
    ADD COLUMN team_application_group_mode VARCHAR(10) NOT NULL DEFAULT 'OFF' COMMENT '申請時のグループ選択: OFF / OPTIONAL / REQUIRED',
    ADD COLUMN team_application_guidance VARCHAR(500) NULL COMMENT '申請フォームに表示する案内文',
    ADD CONSTRAINT chk_organizations_team_app_group_mode CHECK (team_application_group_mode IN ('OFF','OPTIONAL','REQUIRED'));
