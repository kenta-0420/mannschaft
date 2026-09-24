-- ⚠ 破壊的変更: batch_job_logs の既存インデックス idx_bjl_job を削除する
--
-- 削除してよい根拠: 本ファイルで新設する idx_bjl_job_started_id
-- (job_name, started_at DESC, id DESC) は idx_bjl_job (job_name, started_at DESC) を
-- 左前置として完全に含む。旧索引が賄っていた絞り込み・整列はすべて新索引が賄うため、
-- 両方を残すと書き込みのたびに二重更新するコストだけが増える。
-- 影響範囲は batch_job_logs の索引のみで、行データは一切変更しない（DELETE / DROP COLUMN は無い）。
--
-- ロールバック手順（本 migration を巻き戻す必要が生じた場合）:
--   CREATE INDEX idx_bjl_job ON batch_job_logs (job_name, started_at DESC);
--   DROP INDEX idx_bjl_job_started_id ON batch_job_logs;
--
-- ────────────────────────────────────────────────────────────────
-- 背景（Codex 検分4巡目の指摘）:
--   started_at は DATETIME で秒精度しか持たない。手動実行とスケジュール実行が重なると、
--   通常実行行と後発の SKIPPED 行が同一秒になりうる。started_at だけの順序では
--   旧い行が選ばれ、「停止中なのに動いていた」と誤判定してスキップ行を重複記録し、
--   管理 API も誤った直近状態を表示する。よって (started_at DESC, id DESC) の全順序で引く。
--
--   既存の idx_bjl_job では ORDER BY started_at DESC, id DESC を索引で賄えず filesort に落ちる。
--   実測（開発DB・933,011行 / 対象ジョブ 81,433行）:
--     既存索引 : Extra=Using filesort / 実読み 81,996行 / 2,596 ms
--     本索引   : Extra=NULL           / 実読み      1行 /  0.025 ms
--
-- ⚠ 文の順序を入れ替えてはならない（CREATE を先、DROP を後にすること。Codex 検分5巡目の指摘）:
--   (1) 性能: 約93万行の本番相当テーブルでは索引作成に時間がかかる。先に DROP すると、
--       その間 job_name 用の索引が一切存在せず、稼働中のタスクによる履歴・状態取得が
--       全件走査＋filesort に退化する。CREATE を先に済ませれば無索引の瞬間が生じない。
--   (2) 復旧可能性: MySQL の DDL は非トランザクションであり、途中で失敗しても巻き戻らない。
--       先に DROP した後で CREATE が容量不足やタイムアウトで失敗すると、旧索引は戻らず
--       新索引も無い「索引が消えたまま」の状態で固定される。さらに同じ migration を
--       再実行しても、既に消えている idx_bjl_job の DROP INDEX で必ず失敗するため
--       （規約により IF EXISTS は付けない）、手作業なしでは復旧できない。
--       CREATE を先にすれば、失敗しても旧索引が残っており稼働は継続する。
-- ────────────────────────────────────────────────────────────────

-- (1) 新しい索引を作成する。ここが失敗しても旧索引 idx_bjl_job は健在で、稼働に影響しない。
CREATE INDEX idx_bjl_job_started_id
    ON batch_job_logs (job_name, started_at DESC, id DESC);

-- (2) 作成に成功した後で、左前置が重複する旧索引を削除する。
DROP INDEX idx_bjl_job ON batch_job_logs;
