-- F04.1 タイムライン投票テーブル
CREATE TABLE timeline_polls (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    timeline_post_id BIGINT       NOT NULL,
    question         VARCHAR(200) NOT NULL,
    total_vote_count INT          NOT NULL DEFAULT 0,
    expires_at       DATETIME     NULL,
    is_closed        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_polls_post FOREIGN KEY (timeline_post_id) REFERENCES timeline_posts(id) ON DELETE CASCADE,
    UNIQUE KEY uk_polls_post (timeline_post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
