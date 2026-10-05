-- 認証本人の申請履歴を申請時requesterで絞り、同時刻もID降順で安定して取得する。
CREATE INDEX idx_vjr_requester_created_at_id
    ON village_join_requests (requester_user_id, created_at DESC, id DESC);
