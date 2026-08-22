-- Gate 基盤工事④-A: batch_job_logs の「直近1行」を全順序で引くための索引。
--
-- 背景（Codex 検分4巡目の指摘）:
--   started_at は DATETIME で秒精度しか持たない。手動実行とスケジュール実行が重なると、
--   通常実行行と後発の SKIPPED 行が同一秒になりうる。started_at だけの順序では
--   旧い行が選ばれ、「停止中なのに動いていた」と誤判定してスキップ行を重複記録し、
--   管理 API も誤った直近状態を表示する。よって (started_at DESC, id DESC) の全順序で引く。
--
-- 既存の idx_bjl_job (job_name, started_at DESC) では ORDER BY started_at DESC, id DESC を
-- 索引で賄えず filesort に落ちる。実測（開発DB・933,011行 / 対象ジョブ 81,433行）:
--   既存索引        : Extra=Using filesort / 実読み 81,996行 / 2,596 ms
--   本索引          : Extra=NULL           / 実読み      1行 /  0.054 ms
--
-- idx_bjl_job は本索引の左前置（job_name, started_at DESC）と重複するため削除する。
-- 本索引が同じ絞り込みを完全に賄い、索引が二重に存在すると書き込みコストだけが増える。
DROP INDEX idx_bjl_job ON batch_job_logs;

CREATE INDEX idx_bjl_job_started_id
    ON batch_job_logs (job_name, started_at DESC, id DESC);
