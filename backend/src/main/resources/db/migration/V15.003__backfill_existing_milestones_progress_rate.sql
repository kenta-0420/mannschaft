-- F02.7: 既存マイルストーンの progress_rate / position バックフィル
-- 既存マイルストーンの progress_rate を TODO 完了率から計算して更新
-- F02.3 準拠で TODO ステータスは OPEN / IN_PROGRESS / COMPLETED の3種。論理削除済みは deleted_at で判定
UPDATE project_milestones pm
JOIN (
  SELECT
    milestone_id,
    COUNT(*) AS total,
    SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed
  FROM todos
  WHERE milestone_id IS NOT NULL
    AND deleted_at IS NULL
  GROUP BY milestone_id
) t ON pm.id = t.milestone_id
SET pm.progress_rate = ROUND(t.completed * 100.0 / t.total, 2);

-- position は既存 TODO の作成順を踏襲（created_at 昇順で 0, 1, 2, ...）
-- MySQL 8.0 の ROW_NUMBER を使用
-- Flyway により一度だけ実行される（チェックサム管理）ため冪等性は不要
UPDATE todos t1
JOIN (
  SELECT
    id,
    ROW_NUMBER() OVER (PARTITION BY milestone_id ORDER BY created_at, id) - 1 AS pos
  FROM todos
  WHERE milestone_id IS NOT NULL
    AND deleted_at IS NULL
) t2 ON t1.id = t2.id
SET t1.position = t2.pos;
