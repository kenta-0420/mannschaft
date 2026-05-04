-- F08.7 Phase 9-δ: 予算閾値超過警告テーブル
-- 設計書 F08.7 (v1.2) §5.5 / §5.7 / §6.2.5 に準拠。
--
-- 1 レコード = (allocation, 閾値) ペアの「警告発火履歴」。
-- 同一割当で複数閾値（80% / 100% / 120%）に達した場合は閾値ごとに 1 件記録される。
-- UNIQUE (allocation_id, threshold_percent) により同じ閾値での重複検知を防ぐ。
--
-- 確認応答（acknowledge）は acknowledged_at / acknowledged_by の更新で表現する。
-- 物理削除は禁止（監査履歴保持）。
--
-- workflow_request_id は F05.6 連携用（100% 到達時のみセット）。
-- 設計書 §8.4: ワークフロー定義は budget_configs.over_limit_workflow_id（V11.034 で追加）が指定する。

CREATE TABLE budget_threshold_alerts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    allocation_id BIGINT UNSIGNED NOT NULL,
    threshold_percent SMALLINT UNSIGNED NOT NULL,
    triggered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    consumed_amount_at_trigger DECIMAL(12,0) NOT NULL,
    notified_user_ids JSON NOT NULL,
    workflow_request_id BIGINT UNSIGNED DEFAULT NULL,
    acknowledged_at DATETIME DEFAULT NULL,
    acknowledged_by BIGINT UNSIGNED DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),

    -- UNIQUE 制約（設計書 §5.5 準拠）
    -- 同一割当 × 同一閾値 で重複発火させない（再評価時の冪等性保証）
    CONSTRAINT uq_bta_allocation_threshold UNIQUE (allocation_id, threshold_percent),

    -- インデックス（設計書 §5.5 準拠）
    INDEX idx_bta_triggered (triggered_at),
    -- 未確認一覧用（acknowledged_at IS NULL の行を高速絞り込み）
    -- MySQL 8 に partial index は無いが、acknowledged_at が NULL の行も通常 INDEX に含まれるため
    -- WHERE acknowledged_at IS NULL での走査は INDEX RANGE SCAN で高速化される
    INDEX idx_bta_unack (acknowledged_at, allocation_id),

    -- CHECK 制約（設計書 §5.5 準拠）
    -- 閾値は 80 / 100 / 120 のいずれか
    CONSTRAINT chk_bta_threshold CHECK (threshold_percent IN (80, 100, 120)),
    CONSTRAINT chk_bta_consumed_amount CHECK (consumed_amount_at_trigger >= 0),

    -- FK 制約
    -- allocation: 設計書 §5.5 では CASCADE。ただし V11.030 の運用方針（割当の物理削除不可・論理削除のみ）
    -- に整合するため、設計書記載通り CASCADE を採用。論理削除時は alert は残置する（acknowledged_at で運用）。
    -- 仮に物理削除が発生しても警告履歴も連動して消える（孤児レコード防止）。
    CONSTRAINT fk_bta_allocation FOREIGN KEY (allocation_id)
        REFERENCES shift_budget_allocations(id) ON DELETE CASCADE,
    -- acknowledged_by: ユーザー削除時は NULL に（履歴は残す）
    CONSTRAINT fk_bta_acked_by FOREIGN KEY (acknowledged_by)
        REFERENCES users(id) ON DELETE SET NULL
    -- workflow_request_id への FK は workflow_requests テーブル投入後に追加検討。
    -- 設計書 §5.5 では ON DELETE SET NULL。F05.6 完成度に応じて将来追加マイグレーションで対応。
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
