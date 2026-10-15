-- content_reports.description を VARCHAR(1000) → TEXT に変更
-- エンティティの columnDefinition = "TEXT" と一致させる
ALTER TABLE content_reports MODIFY COLUMN description TEXT NULL;
