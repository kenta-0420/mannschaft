-- CMP-260909-2152 α: work_date を全状態の行（CANCELLED・論理削除を含む）で shift_slots.slot_date から埋める（recorded_at は使わない）。
-- 枠が無い行は NULL のまま残し、後続の NOT NULL 化で移行エラーにする（推測値は入れない）。
UPDATE shift_budget_consumptions c
    JOIN shift_slots s ON s.id = c.slot_id
SET c.work_date = s.slot_date
WHERE c.work_date IS NULL;
