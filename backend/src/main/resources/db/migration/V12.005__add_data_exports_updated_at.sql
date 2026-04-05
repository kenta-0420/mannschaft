-- BaseEntity の updated_at カラムを data_exports テーブルに追加する。
-- DataExportEntity が BaseEntity を継承しているため、JPA の @PreUpdate が更新日時を管理する。
ALTER TABLE data_exports ADD COLUMN updated_at DATETIME NULL;
