-- seed-e2e-data.js が誤った列挙値でINSERTしたレコードを修正する。
-- ScheduleVisibility: 'PUBLIC' → 'MEMBERS_ONLY'
-- MinViewRole / MinResponseRole: 'MEMBER' → 'MEMBER_PLUS'
-- ScheduleStatus: 'CONFIRMED' → 'SCHEDULED'
-- CommentOption: 'ALLOWED' → 'OPTIONAL'
-- 参考: V12.012__fix_schedules_attendance_status_pending.sql（同パターンの先例）

UPDATE schedules SET visibility = 'MEMBERS_ONLY' WHERE visibility = 'PUBLIC';
UPDATE schedules SET min_view_role = 'MEMBER_PLUS' WHERE min_view_role = 'MEMBER';
UPDATE schedules SET min_response_role = 'MEMBER_PLUS' WHERE min_response_role = 'MEMBER';
UPDATE schedules SET status = 'SCHEDULED' WHERE status = 'CONFIRMED';
UPDATE schedules SET comment_option = 'OPTIONAL' WHERE comment_option = 'ALLOWED';
