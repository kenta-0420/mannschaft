CREATE TABLE otp_challenges (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT UNSIGNED NOT NULL,
    purpose       VARCHAR(30)  NOT NULL,
    code_hash     VARCHAR(200) NOT NULL,
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    locked_until  DATETIME,
    expires_at    DATETIME     NOT NULL,
    used_at       DATETIME,
    created_at    DATETIME     NOT NULL,

    CONSTRAINT fk_otp_challenges_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_otp_challenges_user_purpose ON otp_challenges(user_id, purpose);
