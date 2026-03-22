-- =====================================================================
-- 個人情報カラムの暗号化対応
-- VARCHAR → TEXT（Base64暗号文格納）、検索用HMACハッシュカラム追加
-- =====================================================================

-- ----- users -----
ALTER TABLE users
    ADD COLUMN postal_code TEXT NULL COMMENT '郵便番号（AES-256-GCM暗号化）' AFTER phone_number,
    MODIFY COLUMN last_name TEXT NOT NULL COMMENT '姓（AES-256-GCM暗号化）',
    MODIFY COLUMN first_name TEXT NOT NULL COMMENT '名（AES-256-GCM暗号化）',
    MODIFY COLUMN last_name_kana TEXT NULL COMMENT '姓カナ（AES-256-GCM暗号化）',
    MODIFY COLUMN first_name_kana TEXT NULL COMMENT '名カナ（AES-256-GCM暗号化）',
    MODIFY COLUMN phone_number TEXT NULL COMMENT '電話番号（AES-256-GCM暗号化）',
    ADD COLUMN last_name_hash CHAR(64) NULL COMMENT '姓のHMACハッシュ（検索用）' AFTER postal_code,
    ADD COLUMN first_name_hash CHAR(64) NULL COMMENT '名のHMACハッシュ（検索用）' AFTER last_name_hash,
    ADD COLUMN phone_number_hash CHAR(64) NULL COMMENT '電話番号のHMACハッシュ（検索用）' AFTER first_name_hash,
    ADD COLUMN encryption_key_version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '暗号化キーバージョン' AFTER phone_number_hash;

CREATE INDEX idx_users_last_name_hash ON users(last_name_hash);
CREATE INDEX idx_users_phone_hash ON users(phone_number_hash);

-- ----- resident_registry -----
ALTER TABLE resident_registry
    MODIFY COLUMN last_name TEXT NOT NULL COMMENT '姓（AES-256-GCM暗号化）',
    MODIFY COLUMN first_name TEXT NOT NULL COMMENT '名（AES-256-GCM暗号化）',
    MODIFY COLUMN last_name_kana TEXT NULL COMMENT '姓カナ（AES-256-GCM暗号化）',
    MODIFY COLUMN first_name_kana TEXT NULL COMMENT '名カナ（AES-256-GCM暗号化）',
    MODIFY COLUMN phone TEXT NULL COMMENT '電話番号（AES-256-GCM暗号化）',
    MODIFY COLUMN email TEXT NULL COMMENT 'メール（AES-256-GCM暗号化）',
    MODIFY COLUMN emergency_contact TEXT NULL COMMENT '緊急連絡先（AES-256-GCM暗号化）',
    ADD COLUMN last_name_hash CHAR(64) NULL COMMENT '姓のHMACハッシュ（検索用）' AFTER emergency_contact,
    ADD COLUMN first_name_hash CHAR(64) NULL COMMENT '名のHMACハッシュ（検索用）' AFTER last_name_hash,
    ADD COLUMN encryption_key_version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '暗号化キーバージョン' AFTER first_name_hash;

CREATE INDEX idx_rr_last_name_hash ON resident_registry(last_name_hash);

-- ----- receipt_issuer_settings -----
ALTER TABLE receipt_issuer_settings
    MODIFY COLUMN issuer_name TEXT NOT NULL COMMENT '発行者名（AES-256-GCM暗号化）',
    MODIFY COLUMN postal_code TEXT NULL COMMENT '郵便番号（AES-256-GCM暗号化）',
    MODIFY COLUMN address TEXT NULL COMMENT '住所（AES-256-GCM暗号化）',
    MODIFY COLUMN phone TEXT NULL COMMENT '電話番号（AES-256-GCM暗号化）',
    ADD COLUMN encryption_key_version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '暗号化キーバージョン' AFTER auto_reset_number;

-- ----- receipts -----
-- FULLTEXT インデックスは暗号文では無意味なので削除
ALTER TABLE receipts DROP INDEX ft_r_recipient;

ALTER TABLE receipts
    MODIFY COLUMN recipient_name TEXT NOT NULL COMMENT '受領者名（AES-256-GCM暗号化）',
    MODIFY COLUMN recipient_postal_code TEXT NULL COMMENT '受領者郵便番号（AES-256-GCM暗号化）',
    MODIFY COLUMN recipient_address TEXT NULL COMMENT '受領者住所（AES-256-GCM暗号化）',
    MODIFY COLUMN issuer_name TEXT NOT NULL COMMENT '発行者名（AES-256-GCM暗号化）',
    MODIFY COLUMN issuer_postal_code TEXT NULL COMMENT '発行者郵便番号（AES-256-GCM暗号化）',
    MODIFY COLUMN issuer_address TEXT NULL COMMENT '発行者住所（AES-256-GCM暗号化）',
    MODIFY COLUMN issuer_phone TEXT NULL COMMENT '発行者電話番号（AES-256-GCM暗号化）',
    ADD COLUMN encryption_key_version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '暗号化キーバージョン' AFTER voided_reason;
