-- F08.7 Phase 9-β: シフト予算消化記録テーブル
-- 設計書 F08.7 (v1.2) §5.3 / §5.7 / §11 / §11.1 に準拠。
--
-- 1 レコード = 1 (slot, user) ペア。シフト公開時に PLANNED で INSERT され、
-- 月次締めで CONFIRMED へ昇格、シフトキャンセル等で CANCELLED へ遷移する。
-- 物理削除禁止・status 遷移で表現する（運用ルールは §5.3 / §11 参照）。

CREATE TABLE shift_budget_consumptions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    allocation_id BIGINT UNSIGNED NOT NULL,
    shift_id BIGINT UNSIGNED NOT NULL,
    slot_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    hourly_rate_snapshot DECIMAL(10,2) NOT NULL,
    hours DECIMAL(5,2) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'JPY',
    status VARCHAR(20) NOT NULL DEFAULT 'PLANNED',
    recorded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at DATETIME DEFAULT NULL,
    cancelled_at DATETIME DEFAULT NULL,
    cancel_reason VARCHAR(40) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME DEFAULT NULL,
    -- UNIQUE 用 STORED 生成カラム: MySQL の UNIQUE は NULL を「異なる値」と扱うため
    deleted_at_uq DATETIME AS (COALESCE(deleted_at, '9999-12-31 00:00:00')) STORED NOT NULL,
    PRIMARY KEY (id),

    -- インデックス（設計書 §5.3 準拠）
    INDEX idx_sbc_allocation_status (allocation_id, status),
    INDEX idx_sbc_slot (slot_id),
    INDEX idx_sbc_user_recorded (user_id, recorded_at),
    INDEX idx_sbc_shift (shift_id, status),

    -- UNIQUE 制約（§11.1 同一 (slot, user) 再 INSERT パターンに対応）
    -- CANCELLED は status 違いで再記録可能（旧 PLANNED を CANCELLED に遷移後、新規 PLANNED を INSERT）。
    -- deleted_at を含めて論理削除との並存を許可（運用ミスに備える）。
    -- NULL を区別するため STORED 生成カラム経由で UNIQUE を張る
    CONSTRAINT uq_sbc_slot_user_status UNIQUE (slot_id, user_id, status, deleted_at_uq),

    -- CHECK 制約（設計書 §5.3 準拠）
    CONSTRAINT chk_sbc_amount CHECK (amount >= 0 AND hours >= 0),
    CONSTRAINT chk_sbc_status CHECK (status IN ('PLANNED', 'CONFIRMED', 'CANCELLED')),

    -- FK 制約（設計書 §5.3: 全て ON DELETE RESTRICT）
    CONSTRAINT fk_sbc_allocation FOREIGN KEY (allocation_id)
        REFERENCES shift_budget_allocations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_sbc_shift FOREIGN KEY (shift_id)
        REFERENCES shift_schedules(id) ON DELETE RESTRICT,
    CONSTRAINT fk_sbc_slot FOREIGN KEY (slot_id)
        REFERENCES shift_slots(id) ON DELETE RESTRICT,
    CONSTRAINT fk_sbc_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
