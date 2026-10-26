-- ユーザーマスターテーブル
CREATE TABLE users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NULL,
    last_name VARCHAR(50) NOT NULL,
    first_name VARCHAR(50) NOT NULL,
    last_name_kana VARCHAR(50) NULL,
    first_name_kana VARCHAR(50) NULL,
    display_name VARCHAR(50) NOT NULL,
    nickname2 VARCHAR(50) NULL,
    is_searchable TINYINT(1) NOT NULL DEFAULT 1,
    avatar_url VARCHAR(500) NULL,
    phone_number VARCHAR(20) NULL,
    locale VARCHAR(10) NOT NULL DEFAULT 'ja',
    timezone VARCHAR(50) NOT NULL DEFAULT 'Asia/Tokyo',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    last_login_at DATETIME NULL,
    reminder_sent_at DATETIME NULL,
    archived_at DATETIME NULL,
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_status CHECK (status IN ('PENDING_VERIFICATION','ACTIVE','FROZEN','ARCHIVED'))
);
CREATE INDEX idx_users_status_last_login ON users(status, last_login_at);
CREATE INDEX idx_users_status_created_at ON users(status, created_at);
CREATE INDEX idx_users_deleted_at ON users(deleted_at);
