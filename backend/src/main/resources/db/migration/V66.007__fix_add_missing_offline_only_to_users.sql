-- 修正: V18.013 が SELECT 1（no-op）に変更されたため offline_only カラムが未追加。
-- UserEntity が参照しているため全API呼び出しが COMMON_999 で落ちる。
ALTER TABLE users
    ADD COLUMN offline_only TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'スマホ・PCを持たない住民フラグ（1=非デジタル住民）'
        AFTER care_notification_enabled;
