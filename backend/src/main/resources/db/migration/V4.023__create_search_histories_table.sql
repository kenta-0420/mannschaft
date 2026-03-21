-- F04.6 グローバル検索: 検索履歴テーブル
CREATE TABLE search_histories (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    query      VARCHAR(100) NOT NULL,
    searched_at DATETIME    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    UNIQUE KEY uk_search_histories_user_query (user_id, query),
    INDEX idx_search_histories_user_searched (user_id, searched_at DESC),
    CONSTRAINT fk_search_histories_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
