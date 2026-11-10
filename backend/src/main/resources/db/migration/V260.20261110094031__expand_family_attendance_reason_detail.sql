-- 暗号化したreason_detailの保存容量をAPI入力上限に合わせて拡張する。
ALTER TABLE family_attendance_notices
    MODIFY COLUMN reason_detail VARCHAR(4040) NULL COMMENT '詳細（健康情報配慮）';
