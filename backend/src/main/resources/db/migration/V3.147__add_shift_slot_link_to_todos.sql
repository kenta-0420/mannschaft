-- F03.5 Phase 4-β: todos テーブルに linked_shift_slot_id カラムを追加
-- シフト公開時に自動作成される Todo の冪等性担保（スロット×ユーザー単位で重複防止）
ALTER TABLE todos
    ADD COLUMN linked_shift_slot_id BIGINT UNSIGNED NULL AFTER linked_schedule_id,
    ADD INDEX idx_todos_linked_shift_slot_id (linked_shift_slot_id),
    ADD CONSTRAINT uq_shift_slot_user UNIQUE (linked_shift_slot_id, scope_id);
