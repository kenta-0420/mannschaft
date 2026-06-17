-- (B) 組織→参加チーム配信 案C フェーズA 隊A
-- アンケートの配信母集団にサポーター（応援者）を含めるかのトグル列を追加する。
-- 既定 false（組織配信時はサポーター除外）。NOT NULL DEFAULT FALSE のため既存行も安全。
-- ※この値を使った母集団絞り込みの配線は後続隊（survey配線）の領域。
ALTER TABLE surveys
    ADD COLUMN include_supporters BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT '配信母集団にサポーター（応援者）を含めるか' AFTER auto_post_to_timeline;
