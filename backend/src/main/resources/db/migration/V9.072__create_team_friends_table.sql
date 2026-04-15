-- V9.072: F01.5 フレンドチーム関係キャッシュテーブル
-- 相互フォロー成立時に自動生成される。team_a_id < team_b_id で正規化。
--
-- 依存テーブル: teams (V2.004), follows (V4.006)
-- 設計書: docs/features/F01.5_team_friend_relationships.md §4.1

CREATE TABLE team_friends (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_a_id       BIGINT UNSIGNED NOT NULL COMMENT 'FK -> teams.id（小さい方のID）',
    team_b_id       BIGINT UNSIGNED NOT NULL COMMENT 'FK -> teams.id（大きい方のID）',
    established_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '相互フォロー成立日時',
    a_follow_id     BIGINT UNSIGNED NOT NULL COMMENT 'A→B follow の follows.id',
    b_follow_id     BIGINT UNSIGNED NOT NULL COMMENT 'B→A follow の follows.id',
    is_public       BOOLEAN         NOT NULL DEFAULT FALSE COMMENT '公開設定（デフォルト非公開）',
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT chk_tf_id_order CHECK (team_a_id < team_b_id),
    CONSTRAINT uq_tf_pair UNIQUE (team_a_id, team_b_id),
    CONSTRAINT fk_tf_team_a   FOREIGN KEY (team_a_id)   REFERENCES teams (id)   ON DELETE CASCADE,
    CONSTRAINT fk_tf_team_b   FOREIGN KEY (team_b_id)   REFERENCES teams (id)   ON DELETE CASCADE,
    CONSTRAINT fk_tf_a_follow FOREIGN KEY (a_follow_id) REFERENCES follows (id) ON DELETE CASCADE,
    CONSTRAINT fk_tf_b_follow FOREIGN KEY (b_follow_id) REFERENCES follows (id) ON DELETE CASCADE,
    INDEX idx_tf_team_a (team_a_id, established_at DESC),
    INDEX idx_tf_team_b (team_b_id, established_at DESC),
    INDEX idx_tf_public (is_public, team_a_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='フレンドチーム関係キャッシュ';
