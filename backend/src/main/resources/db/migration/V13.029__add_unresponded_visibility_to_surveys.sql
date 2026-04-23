-- F05.4 §7.2 未回答者一覧の可視化: surveys に公開範囲カラムを追加
-- 既存レコードはデフォルト 'CREATOR_AND_ADMIN' (既存挙動維持)
ALTER TABLE surveys
    ADD COLUMN unresponded_visibility VARCHAR(30) NOT NULL DEFAULT 'CREATOR_AND_ADMIN'
        COMMENT '未回答者一覧の公開範囲: HIDDEN / CREATOR_AND_ADMIN / ALL_MEMBERS'
        AFTER distribution_mode;
