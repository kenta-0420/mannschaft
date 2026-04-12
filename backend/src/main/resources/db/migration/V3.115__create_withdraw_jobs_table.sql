-- 退会 SAGA 進捗管理テーブル（チェックポイント方式・再開可能）
CREATE TABLE withdraw_jobs (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    user_id              BIGINT        NOT NULL COMMENT 'users テーブルとは非FK（退会処理中に削除される）',
    status               VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                         COMMENT 'PENDING / IN_PROGRESS / COMPLETED / FAILED / BLOCKED_MANUAL',
    current_step         INT           NOT NULL DEFAULT 1 COMMENT '1-7',
    step_1_completed     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'PERSONAL タグ削除',
    step_2_completed     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'TEAM タグ移譲',
    step_3_completed     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'ORGANIZATION タグ移譲',
    step_4_completed     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'quick_memos 物理削除（S3含む）',
    step_5_completed     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'user_quick_memo_settings 物理削除',
    step_6_completed     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '監査ログ匿名化',
    step_7_completed     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'users 物理削除',
    last_error_message   TEXT          NULL,
    last_error_step      INT           NULL,
    retry_count          INT           NOT NULL DEFAULT 0,
    started_at           DATETIME(6)   NULL,
    completed_at         DATETIME(6)   NULL,
    created_at           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退会SAGAジョブ進捗管理';

CREATE INDEX idx_withdraw_jobs_user_status ON withdraw_jobs (user_id, status);
CREATE INDEX idx_withdraw_jobs_status ON withdraw_jobs (status, retry_count);
