-- Phase 2-A: ホットパス複合インデックス追加
-- audit_logs: user_id 単体インデックスは既存。ユーザー別ページング用複合インデックスを追加
ALTER TABLE audit_logs
    ADD INDEX idx_al_user_created (user_id, created_at DESC);

-- shift_assignments: user_id 単体インデックスは既存。ユーザー別状態フィルタ用複合インデックスを追加
ALTER TABLE shift_assignments
    ADD INDEX idx_shift_assignments_user_status_created (user_id, status, created_at DESC);
