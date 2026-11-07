-- CMP-261012-0910 多要素認証(2FA)の穴埋め: メールOTP方式の追加
-- method: 2FA の主手段（TOTP / EMAIL）。既存行はすべて TOTP 方式として扱う。
-- totp_secret: EMAIL 方式では TOTP 秘密鍵を持たないため NULL 許容へ変更する。
ALTER TABLE two_factor_auth
    ADD COLUMN method VARCHAR(16) NOT NULL DEFAULT 'TOTP' COMMENT '2FA方式（TOTP / EMAIL）' AFTER user_id,
    MODIFY COLUMN totp_secret VARCHAR(500) NULL COMMENT 'AES-256-GCM暗号化済みTOTP秘密鍵（EMAIL方式はNULL）';
