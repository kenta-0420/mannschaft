-- shift_requests の preference カラムを 5段階に更新
ALTER TABLE shift_requests
  MODIFY COLUMN preference ENUM('PREFERRED','AVAILABLE','WEAK_REST','STRONG_REST','ABSOLUTE_REST')
    NOT NULL DEFAULT 'AVAILABLE'
    COMMENT 'シフト希望（5段階）';

-- member_availability_defaults の preference カラムを 5段階に更新
ALTER TABLE member_availability_defaults
  MODIFY COLUMN preference ENUM('PREFERRED','AVAILABLE','WEAK_REST','STRONG_REST','ABSOLUTE_REST')
    NOT NULL DEFAULT 'AVAILABLE'
    COMMENT 'デフォルトシフト希望（5段階）';
