-- F08.7 Phase 10-β: 通知失敗・hook 失敗のリトライキュー
-- 設計書 F08.7 (v1.3 追補) §13 Phase 10-β に準拠。
--
-- 目的:
--   9-δ 第2段で導入された AFTER_COMMIT hook の swallow パターンは
--   main トランザクション保護のため例外を握りつぶす。これによりサイレント
--   失敗が記録のみで終わり、再実行手段がなかった。本テーブルで失敗イベントを
--   永続化し、リトライバッチ + 管理 API で根治運用可能にする。
--
-- 1 レコード = (組織, イベント種別, ソース ID) の失敗履歴。
-- リトライバッチが PENDING / RETRYING を retry_count < 3 まで再実行する。
-- 3 回失敗で EXHAUSTED へ遷移。運用者は管理 API で個別再実行 / 手動補正済マーク可能。
--
-- payload: 元イベントのペイロード（再実行時に必要な情報を JSON で保持）。
--   - CONSUMPTION_RECORD/CANCEL: { "shift_schedule_id": ..., "team_id": ..., "triggered_by_user_id": ... }
--   - THRESHOLD_ALERT:           { "allocation_id": ... }
--   - WORKFLOW_START:            { "allocation_id": ..., "alert_id": ..., "workflow_id": ... }
--   - NOTIFICATION_SEND:         { "user_ids": [...], "type": "...", "title": "...", ... }

CREATE TABLE shift_budget_failed_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    source_id BIGINT UNSIGNED DEFAULT NULL,
    payload JSON NOT NULL,
    error_message TEXT DEFAULT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    last_retried_at DATETIME DEFAULT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),

    -- 管理 API 一覧用（組織配下を新しい順で参照）
    INDEX idx_sbfe_org_status (organization_id, status),
    -- リトライバッチ用（PENDING/RETRYING かつ最終リトライから一定時間経過した行を高速抽出）
    INDEX idx_sbfe_pending (status, last_retried_at),

    -- ステータス制約: PENDING/RETRYING/SUCCEEDED/EXHAUSTED/MANUAL_RESOLVED
    CONSTRAINT chk_sbfe_status CHECK (status IN
        ('PENDING', 'RETRYING', 'SUCCEEDED', 'EXHAUSTED', 'MANUAL_RESOLVED')),

    -- イベント種別制約: 設計書 §13 Phase 10-β に列挙された 5 種類
    CONSTRAINT chk_sbfe_event_type CHECK (event_type IN
        ('CONSUMPTION_RECORD', 'CONSUMPTION_CANCEL',
         'THRESHOLD_ALERT', 'WORKFLOW_START', 'NOTIFICATION_SEND')),

    -- retry_count は 0 以上
    CONSTRAINT chk_sbfe_retry_count CHECK (retry_count >= 0),

    -- FK 制約
    CONSTRAINT fk_sbfe_organization FOREIGN KEY (organization_id)
        REFERENCES organizations(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
