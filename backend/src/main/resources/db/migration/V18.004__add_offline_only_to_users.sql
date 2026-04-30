-- F14.1 代理入力・非デジタル住民対応: スマホ・PCを持たない住民フラグをusersテーブルに追加
ALTER TABLE users
    ADD COLUMN offline_only TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'スマホ・PCを持たない住民フラグ（1=非デジタル住民）'
        AFTER care_notification_enabled,
    ALGORITHM=INPLACE, LOCK=NONE;
