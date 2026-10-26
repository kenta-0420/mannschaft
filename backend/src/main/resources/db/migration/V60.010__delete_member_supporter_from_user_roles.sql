-- F00.5 Phase 4: user_roles から MEMBER/SUPPORTER 行を削除する。
-- memberships テーブルへの完全移行完了（Phase 2〜3 の二重書き込み期間終了）。
-- 着手条件: MembershipConsistencyChecker で 7 日連続差分 0 を確認済み。
-- ロールバック SQL は rollback/V60.010_rollback.sql を参照。

DELETE FROM user_roles
WHERE role_id IN (
    SELECT id FROM roles WHERE name IN ('MEMBER', 'SUPPORTER')
);
