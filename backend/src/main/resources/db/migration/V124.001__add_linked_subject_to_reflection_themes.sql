-- F06.5 Phase 2: reflection_themes に科目紐づけカラムを追加（案B・§11.2）
--
-- 新カラム:
--   linked_subject_name: 科目名で紐づける場合に設定（personal_timetable_slots.subject_name と合わせる）
--   linked_course_code:  履修番号で紐づける場合に補完設定（PERSONAL専用・TEAMは常にNULL）
--
-- 既存行への影響: 新カラムはNULLデフォルト → 既存テーマの動作は無改変（後方互換）

ALTER TABLE reflection_themes
    ADD COLUMN linked_subject_name VARCHAR(200) NULL AFTER linked_slot_id,
    ADD COLUMN linked_course_code  VARCHAR(50)  NULL AFTER linked_subject_name;

CREATE INDEX idx_reflection_themes_user_subject
    ON reflection_themes (user_id, linked_slot_kind, linked_subject_name);
