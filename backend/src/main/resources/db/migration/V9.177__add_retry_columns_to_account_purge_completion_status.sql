-- account_purge_completion_status に retry 追跡カラムを追加（Phase F）
-- 管理者による手動 retry の実行回数と最終実行日時を記録する。
ALTER TABLE account_purge_completion_status
    ADD COLUMN retry_count     TINYINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT 'retry 実行回数（管理者による手動 retry 累計）',
    ADD COLUMN last_retried_at DATETIME(6)      NULL
        COMMENT '最後に retry を実行した日時（管理者操作）';
