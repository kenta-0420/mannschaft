-- teamsテーブルにテンプレートID外部キーを追加
ALTER TABLE teams ADD COLUMN template_id BIGINT UNSIGNED NULL;
ALTER TABLE teams ADD CONSTRAINT fk_teams_template FOREIGN KEY (template_id) REFERENCES team_templates(id) ON DELETE SET NULL;
