-- recipient_mode: SPECIFIC（特定ユーザー指定）または OPEN_CALL（全体公開）
ALTER TABLE shift_swap_requests
  ADD COLUMN recipient_mode VARCHAR(20) NOT NULL DEFAULT 'SPECIFIC'
    COMMENT '受信者モード: SPECIFIC=特定ユーザー / OPEN_CALL=全体公開';

-- target_user_ids: JSON 配列 (SPECIFIC モードの対象ユーザーID一覧)
ALTER TABLE shift_swap_requests
  ADD COLUMN target_user_ids JSON NULL
    COMMENT '交代対象ユーザーIDリスト (SPECIFIC モード時)';

-- 既存データの isOpenCall=true のものを OPEN_CALL に設定
UPDATE shift_swap_requests
  SET recipient_mode = 'OPEN_CALL'
WHERE is_open_call = TRUE;
