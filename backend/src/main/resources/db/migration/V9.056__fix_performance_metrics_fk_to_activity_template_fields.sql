-- performance_metrics.linked_activity_field_id の FK 参照先を
-- 存在しない activity_custom_fields → 実在する activity_template_fields に修正
ALTER TABLE performance_metrics
    DROP FOREIGN KEY fk_pm_linked_field;

ALTER TABLE performance_metrics
    ADD CONSTRAINT fk_pm_linked_field
        FOREIGN KEY (linked_activity_field_id) REFERENCES activity_template_fields (id) ON DELETE SET NULL;
