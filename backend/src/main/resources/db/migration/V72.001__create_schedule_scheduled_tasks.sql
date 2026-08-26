-- 機能55 予定の予約作成（アンケート/出欠）第一陣 — 予約タスク基盤テーブル。
--
-- 親予定の開始時刻に紐づく相対/絶対スケジュールで、アンケート（EventSurvey）または
-- 出欠（ScheduleAttendance）を「予約」しておき、scheduled_at 到来時にバッチが materialize する。
-- 主キーは UUIDv7（CLAUDE.md 原則6）。schedule_id / scope_id はクロスドメイン論理参照のため
-- FK は張らず index のみ（原則1）。organization_id をテナントキーとして保持（原則7）。
CREATE TABLE schedule_scheduled_tasks (
    id                     BINARY(16)    NOT NULL COMMENT 'UUIDv7 主キー（原則6）',
    schedule_id            BIGINT        NOT NULL COMMENT '親予定 schedules.id（FK制約なし・論理参照）',
    organization_id        BIGINT        NOT NULL COMMENT 'テナントキー。team予定なら所属組織のid（原則7）',
    scope_type             VARCHAR(20)   NOT NULL COMMENT 'TEAM / ORGANIZATION',
    scope_id               BIGINT        NOT NULL COMMENT 'スコープ実体ID（team_id または organization_id）',
    task_type              VARCHAR(20)   NOT NULL COMMENT 'SURVEY / ATTENDANCE',
    scheduled_at           DATETIME      NOT NULL COMMENT 'この時刻に materialize する',
    status                 VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CREATED/CANCELLED/FAILED',
    payload_json           JSON          NOT NULL COMMENT 'アンケート定義/出欠設定のスナップショット',
    materialized_entity_id BIGINT        NULL     COMMENT '生成後の実体id（event_survey / schedule_attendance 等）',
    attempt_count          INT           NOT NULL DEFAULT 0 COMMENT 'materialize 試行回数',
    last_error             VARCHAR(1000) NULL     COMMENT '最終失敗理由',
    created_by             BIGINT        NULL     COMMENT '作成者 users.id（FK制約なし）',
    created_at             DATETIME      NOT NULL COMMENT '作成日時',
    updated_at             DATETIME      NOT NULL COMMENT '更新日時',
    deleted_at             DATETIME      NULL     COMMENT '論理削除日時',

    PRIMARY KEY (id),
    INDEX idx_sst_status_scheduled (status, scheduled_at),
    INDEX idx_sst_schedule (schedule_id),
    INDEX idx_sst_org_deleted (organization_id, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='予定の予約作成タスク（アンケート/出欠の遅延 materialize）';
