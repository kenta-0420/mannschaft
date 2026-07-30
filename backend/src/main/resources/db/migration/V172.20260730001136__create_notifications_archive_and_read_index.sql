-- 通知 fan-out 抜本改修 P2: notifications アーカイブ表＋クリーンアップ索引
-- 設計台帳: .claude/campaigns/2026-07-29-fanout-redesign-500k.md（P2・保持 AC-11〜14）
--
-- 目的:
--   1) notifications_archive: 古い通知を退避する退避先（chat_messages_archive の金型に倣う）。
--      メイン notifications の FK/索引を維持したまま旧データを移送する。アーカイブ表は FK なし
--      （参照整合性はアプリ層で保証・原則1）。per-row 状態（is_read/read_at/snoozed_until/priority/
--      scope_type/scope_id/organization_id）を保持し、移送後も履歴の意味を失わない。
--   2) idx_notifications_read_created: 保持バッチ／クリーンアップの WHERE 先頭が (is_read, created_at) の
--      クエリを支える索引。既存 idx_notifications_user_read_created は user_id 先頭のため、
--      「全ユーザー横断で is_read + created_at で古い既読を掃く」用途には効かない（乖離の是正・AC-13）。

CREATE TABLE notifications_archive (
    id                BIGINT           NOT NULL          COMMENT 'notifications.id をそのまま引き継ぐ（採番しない）',
    user_id           BIGINT           NOT NULL,
    organization_id   BIGINT           NULL              COMMENT 'テナント（論理参照・FK なし）',
    notification_type VARCHAR(50)      NOT NULL,
    priority          VARCHAR(10)      NOT NULL DEFAULT 'NORMAL',
    title             VARCHAR(200)     NOT NULL,
    body              VARCHAR(1000)    NULL,
    source_type       VARCHAR(50)      NOT NULL,
    source_id         BIGINT           NULL,
    scope_type        VARCHAR(20)      NOT NULL,
    scope_id          BIGINT           NULL,
    action_url        VARCHAR(500)     NULL,
    actor_id          BIGINT           NULL,
    is_read           BOOLEAN          NOT NULL DEFAULT FALSE,
    read_at           DATETIME         NULL,
    channels_sent     JSON             NULL,
    snoozed_until     DATETIME         NULL,
    created_at        DATETIME         NOT NULL,
    archived_at       DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '移送日時',
    PRIMARY KEY (id),
    KEY idx_notif_arch_user_created (user_id, created_at),
    KEY idx_notif_arch_created_at   (created_at)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='通知アーカイブ（保持期間超の退避先・P2）';

-- 保持バッチ／移送の走査を支える索引（全ユーザー横断で is_read + created_at の古い既読を掃く）。
-- 既存の idx_notifications_user_read_created(user_id, is_read, created_at DESC) は user_id 先頭のため
-- 本用途の先頭列被覆にならない。重複しない新規索引として追加する（AC-13）。
CREATE INDEX idx_notifications_read_created ON notifications (is_read, created_at);
