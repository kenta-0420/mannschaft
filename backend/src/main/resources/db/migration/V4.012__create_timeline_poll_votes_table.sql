-- F04.1 タイムライン投票投票テーブル
CREATE TABLE timeline_poll_votes (
    id                     BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT,
    timeline_poll_id       BIGINT UNSIGNED   NOT NULL,
    timeline_poll_option_id BIGINT UNSIGNED  NOT NULL,
    user_id                BIGINT UNSIGNED NOT NULL,
    created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_poll_votes_poll FOREIGN KEY (timeline_poll_id) REFERENCES timeline_polls(id) ON DELETE CASCADE,
    CONSTRAINT fk_poll_votes_option FOREIGN KEY (timeline_poll_option_id) REFERENCES timeline_poll_options(id) ON DELETE CASCADE,
    CONSTRAINT fk_poll_votes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_poll_votes (timeline_poll_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
