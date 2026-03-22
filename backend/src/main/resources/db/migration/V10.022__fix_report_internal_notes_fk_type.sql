-- report_internal_notes.report_id の型不一致修正（BIGINT UNSIGNED → BIGINT）
-- content_reports.id が BIGINT（signed）のため合わせる
ALTER TABLE report_internal_notes
    DROP FOREIGN KEY fk_rin_report;

ALTER TABLE report_internal_notes
    MODIFY COLUMN report_id BIGINT NOT NULL;

ALTER TABLE report_internal_notes
    ADD CONSTRAINT fk_rin_report FOREIGN KEY (report_id) REFERENCES content_reports (id) ON DELETE CASCADE;
