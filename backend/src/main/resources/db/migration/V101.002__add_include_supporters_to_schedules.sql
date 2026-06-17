-- (B) 組織→参加チーム配信 案C フェーズA 隊A
-- スケジュール（出欠確認）の配信母集団にサポーター（応援者）を含めるかのトグル列を追加する。
-- 既定 false（組織配信時はサポーター除外）。NOT NULL DEFAULT FALSE のため既存行も安全。
-- ※この値を使った母集団絞り込みの配線は後続隊（出欠配線）の領域。
ALTER TABLE schedules
    ADD COLUMN include_supporters BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT '出欠確認の配信母集団にサポーター（応援者）を含めるか' AFTER attendance_required;
