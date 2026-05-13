-- F08.8 Phase 3: 修繕計画項目に議事録メモ列を追加
ALTER TABLE repair_plan_items
    ADD COLUMN minutes_note TINYTEXT NULL COMMENT '議事録メモ（Phase 3 地層タイムライン用）' AFTER tags;
