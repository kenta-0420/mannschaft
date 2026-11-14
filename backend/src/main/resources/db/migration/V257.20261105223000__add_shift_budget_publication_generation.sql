-- CMP-261021-0718: 公開世代で遅延イベントを識別し、CANCELLED の監査履歴を複数保持する。
-- 既存行は世代 0 とし、金額・状態・時刻・取消理由・論理削除生成列は変更しない。
-- PLANNED / CONFIRMED は各状態の既存一意性を維持する（両状態の相互排他は追加しない）。
-- 2 表の DDL 全体の原子性・無停止は保証せず、世代非対応 writer との共存は行わない。
-- CANCELLED 履歴が複数できた後は、旧 UNIQUE へ単純には戻せない。

ALTER TABLE shift_schedules
    ADD COLUMN publication_generation BIGINT NOT NULL DEFAULT 0;

ALTER TABLE shift_budget_consumptions
    ADD COLUMN publication_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN active_status VARCHAR(20)
        GENERATED ALWAYS AS (
            CASE WHEN status = 'CANCELLED' THEN NULL ELSE status END
        ) STORED,
    DROP INDEX uq_sbc_slot_user_status,
    ADD CONSTRAINT uq_sbc_slot_user_status
        UNIQUE (slot_id, user_id, active_status, deleted_at_uq);
