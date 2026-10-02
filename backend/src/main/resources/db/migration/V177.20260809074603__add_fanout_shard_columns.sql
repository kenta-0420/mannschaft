-- 通知 fan-out 抜本改修 CMP-001⑤: ワーカー並列化のシャーディング用列追加（出陣-1・スキーマ土台のみ）
--
-- 目的: notification_fanout_jobs を複数ワーカーで水平分割して処理できるよう、
--       ジョブが属するシャード番号（shard_index）と、その時点の総シャード数（shard_count）を
--       ジョブ行に埋め込む。ワーカーは自分の担当 shard_index のみを WHERE 句で絞り込む
--       （enqueue 時のシャード算出ロジック自体は本 migration の対象外＝出陣-3 担当）。
--
-- 後方互換: 既存行・既存経路（VILLAGE/TEAM/ORG 単一経路）は
--   shard_index=0 / shard_count=1 のデフォルト値により、単一ワーカーが全件を担当する
--   従来どおりの挙動のまま変わらない（enqueue 側の書き込みロジック変更は別チケット）。
ALTER TABLE notification_fanout_jobs
    ADD COLUMN shard_index SMALLINT NOT NULL DEFAULT 0
        COMMENT 'このジョブが属するシャード番号（0始まり）。既定 0=単一ワーカー担当（後方互換）',
    ADD COLUMN shard_count SMALLINT NOT NULL DEFAULT 1
        COMMENT 'enqueue 時点の総シャード数。既定 1=シャーディング未使用（後方互換）';

-- 冪等ユニーク制約に shard_index を追加して再作成する。
-- 旧: (scope_type, scope_ref, notification_type, source_event_uuid)
-- 新: (scope_type, scope_ref, notification_type, source_event_uuid, shard_index)
-- 既存行は shard_index=0 固定のため、旧制約と同じ組で一意性が保たれる（後方互換・挙動不変）。
ALTER TABLE notification_fanout_jobs
    DROP INDEX uk_fanout_idempotency,
    ADD UNIQUE KEY uk_fanout_idempotency
        (scope_type, scope_ref, notification_type, source_event_uuid, shard_index)
        COMMENT '同一 fan-out・同一シャードの二重 enqueue を DB レベルで拒否（AC-1 拡張）';
