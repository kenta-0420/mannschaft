-- チームブロックテーブル
CREATE TABLE team_blocks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    blocked_by BIGINT UNSIGNED NOT NULL,
    reason VARCHAR(500) NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_team_blocks_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_team_blocks_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_team_blocks_blocked_by FOREIGN KEY (blocked_by) REFERENCES users (id),
    CONSTRAINT uq_team_blocks UNIQUE (team_id, user_id)
);
