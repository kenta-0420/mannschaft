-- F02.3拡張 Phase1: todos.linked_schedule_id にFKを追加
-- V13.015でschedules側が確立したため、ここでtodos側のFKを安全に追加できる
ALTER TABLE todos
    ADD CONSTRAINT fk_todos_schedules
        FOREIGN KEY (linked_schedule_id) REFERENCES schedules(id) ON DELETE SET NULL;
