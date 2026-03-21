-- F04.1 タイムライン投票選択肢テーブル
CREATE TABLE timeline_poll_options (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    timeline_poll_id BIGINT       NOT NULL,
    option_text      VARCHAR(100) NOT NULL,
    vote_count       INT          NOT NULL DEFAULT 0,
    sort_order       SMALLINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_poll_options_poll FOREIGN KEY (timeline_poll_id) REFERENCES timeline_polls(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
