-- users テーブルに不足している reporting_restricted カラムを追加する
ALTER TABLE users
    ADD COLUMN reporting_restricted TINYINT(1) NOT NULL DEFAULT 0 AFTER reminder_sent_at;
