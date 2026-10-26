-- F01.4: コイントス結果の履歴
CREATE TABLE coin_toss_results (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id         BIGINT UNSIGNED NOT NULL,
    user_id         BIGINT UNSIGNED NOT NULL,
    mode            VARCHAR(20)     NOT NULL DEFAULT 'COIN' COMMENT 'COIN / CUSTOM',
    options         JSON            NOT NULL COMMENT '選択肢の配列',
    result_index    TINYINT         NOT NULL COMMENT '当選した選択肢のインデックス（0-based）',
    question        VARCHAR(200)    NULL     COMMENT '質問文',
    shared_to_chat  BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_ctr_team (team_id, created_at DESC),
    CONSTRAINT fk_ctr_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT fk_ctr_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
