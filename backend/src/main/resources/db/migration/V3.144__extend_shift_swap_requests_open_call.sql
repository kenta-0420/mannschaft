ALTER TABLE shift_swap_requests
  ADD COLUMN is_open_call      BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'オープンコール（不特定多数募集）',
  ADD COLUMN target_user_id    BIGINT UNSIGNED NULL COMMENT '指定交代相手（is_open_call=falseの場合）',
  ADD COLUMN claimed_by        BIGINT UNSIGNED NULL COMMENT '手挙げユーザー（先着1名）',
  ADD COLUMN claimed_at        DATETIME NULL,
  ADD COLUMN version           INT UNSIGNED NOT NULL DEFAULT 0,
  ADD INDEX idx_shift_swap_requests_is_open_call (is_open_call),
  ADD INDEX idx_shift_swap_requests_claimed_by (claimed_by),
  ADD CONSTRAINT fk_shift_swap_requests_target_user FOREIGN KEY (target_user_id) REFERENCES users(id),
  ADD CONSTRAINT fk_shift_swap_requests_claimed_by FOREIGN KEY (claimed_by) REFERENCES users(id);

-- status ENUM を拡張（MySQLはENUM値の追加はALTER MODIFYで行う）
ALTER TABLE shift_swap_requests
  MODIFY COLUMN status ENUM('PENDING','ACCEPTED','APPROVED','REJECTED','CANCELLED','OPEN_CALL','CLAIMED')
    NOT NULL DEFAULT 'PENDING';
