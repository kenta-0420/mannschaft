-- F02.3拡張 Phase1: todosテーブルに進捗管理・ガントバー・スケジュール連携カラムを追加
-- linked_schedule_id のFKは循環依存回避のためV13.016で追加する
ALTER TABLE todos
    ADD COLUMN start_date           DATE              NULL               COMMENT '開始日（ガントバー表示用。NULLの場合はガントバー対象外）'  AFTER due_time,
    ADD COLUMN linked_schedule_id   BIGINT UNSIGNED   NULL               COMMENT '連携スケジュールID（FKはV13.016で追加）'                  AFTER start_date,
    ADD COLUMN progress_rate        DECIMAL(5,2)      NOT NULL DEFAULT 0.00 COMMENT '進捗率（0.00〜100.00。手動設定またはサブタスク完了率から算出）' AFTER linked_schedule_id,
    ADD COLUMN progress_manual      TINYINT(1)        NOT NULL DEFAULT 0  COMMENT '進捗率手動設定フラグ（1=手動設定中・自動更新を停止）'      AFTER progress_rate,
    ADD UNIQUE KEY uq_todos_linked_schedule (linked_schedule_id),
    ADD INDEX idx_todo_range (scope_type, scope_id, start_date, due_date);
