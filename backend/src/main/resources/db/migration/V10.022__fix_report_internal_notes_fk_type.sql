-- report_internal_notes.report_id の型を BIGINT UNSIGNED に統一
-- content_reports.id が BIGINT UNSIGNED のため合わせる
ALTER TABLE report_internal_notes
    DROP FOREIGN KEY fk_rin_report;

ALTER TABLE report_internal_notes
    MODIFY COLUMN report_id BIGINT UNSIGNED NOT NULL;

ALTER TABLE report_internal_notes
    ADD CONSTRAINT fk_rin_report FOREIGN KEY (report_id) REFERENCES content_reports (id) ON DELETE CASCADE;
