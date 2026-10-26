-- F08.7 Phase 9-γ 前提条件: shift_schedules への プロジェクト紐付カラム追加
-- 設計書 F08.7 (v1.2) §12.1 に基づく F03.5 領域への拡張
-- - linked_project_id: 1:1 関係、NULL 許容（紐付なし）
-- - ON DELETE SET NULL: プロジェクト削除時はリンク解除のみ、シフトは残す
ALTER TABLE shift_schedules
    ADD COLUMN linked_project_id BIGINT UNSIGNED NULL
        COMMENT 'F08.7 シフト-予算-TODO 連携: 紐付プロジェクトID',
    ADD CONSTRAINT fk_ss_linked_project
        FOREIGN KEY (linked_project_id) REFERENCES projects (id) ON DELETE SET NULL,
    ADD INDEX idx_ss_linked_project (linked_project_id);
