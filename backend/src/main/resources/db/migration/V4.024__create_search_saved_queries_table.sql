-- F04.6 グローバル検索: 保存済み検索クエリテーブル
CREATE TABLE search_saved_queries (
    id           BIGINT UNSIGNED      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT UNSIGNED NOT NULL,
    name         VARCHAR(50) NOT NULL,
    query_params JSON        NOT NULL,
    created_at   DATETIME    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    INDEX idx_search_saved_queries_user_created (user_id, created_at DESC),
    CONSTRAINT fk_search_saved_queries_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
