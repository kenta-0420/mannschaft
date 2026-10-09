-- 本人の各未完了状態を更新時刻・ID順の先頭一件で読み、履歴全体のsortを避ける。
CREATE INDEX idx_diagnosis_sessions_user_status_updated_id
    ON diagnosis_sessions (user_id, status, updated_at DESC, id DESC);
