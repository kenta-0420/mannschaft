-- 個人ユーザー間ブロック機能。DM・フォロー・通知等の複数機能で横断的に使用する。
CREATE TABLE user_blocks (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    blocker_id BIGINT UNSIGNED NOT NULL COMMENT 'ブロックした側のユーザーID',
    blocked_id BIGINT UNSIGNED NOT NULL COMMENT 'ブロックされた側のユーザーID',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_ub_blocker FOREIGN KEY (blocker_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_ub_blocked FOREIGN KEY (blocked_id) REFERENCES users (id) ON DELETE CASCADE,
    UNIQUE KEY uq_user_blocks (blocker_id, blocked_id),
    INDEX idx_user_blocks_blocked (blocked_id, blocker_id)
);
