-- AttendanceGenerationStatus enum に PENDING が存在しないため、
-- シードデータ等で誤って挿入された 'PENDING' を正しい値 'READY' に修正する。
UPDATE schedules
SET attendance_status = 'READY'
WHERE attendance_status = 'PENDING';
