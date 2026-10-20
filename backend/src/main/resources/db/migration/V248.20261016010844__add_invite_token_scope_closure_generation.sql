-- 個人招待が発行された時点の招待先自身の閉鎖世代を記録する（#3892）。
-- 本番既存データはない。開発行のID・token・期限・使用回数・既失効日時を変更しない。
-- 親の現在世代へbackfillすると過去の閉鎖前tokenを復活させるため、既存行は0のまま保全する。
ALTER TABLE invite_tokens
    ADD COLUMN scope_closure_generation BIGINT NOT NULL DEFAULT 0
    COMMENT '招待先自身の発行時閉鎖世代。解除しても旧招待を復元しない';
