-- F02.3拡張 Phase1: schedulesテーブルにTODO連携カラムを追加
-- todos側のFKはV13.016で追加（循環依存回避のため先にschedules側を確立する）
ALTER TABLE schedules
    ADD COLUMN linked_todo_id BIGINT UNSIGNED NULL COMMENT '連携TODOのID（スケジュールとTODOを1:1で紐付け）' AFTER recurrence_rule,
    ADD UNIQUE KEY uq_schedules_linked_todo (linked_todo_id),
    ADD INDEX idx_schedules_linked_todo (linked_todo_id),
    ADD CONSTRAINT fk_schedules_todos
        FOREIGN KEY (linked_todo_id) REFERENCES todos(id) ON DELETE SET NULL;
