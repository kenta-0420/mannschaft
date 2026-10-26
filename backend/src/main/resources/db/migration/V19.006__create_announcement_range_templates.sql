-- F02.8 ダッシュボード告知ウィザード: 範囲テンプレートテーブル作成
CREATE TABLE announcement_range_templates
(
    id                BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    scope_type        ENUM ('TEAM', 'ORGANIZATION') NOT NULL COMMENT '適用スコープ種別',
    scope_id          BIGINT UNSIGNED  NOT NULL COMMENT '適用スコープID (teams.id / organizations.id)',
    name              VARCHAR(100)     NOT NULL COMMENT 'テンプレート名（1〜100文字）',
    target_role       ENUM ('MEMBERS_AND_ABOVE', 'SUPPORTERS_AND_ABOVE', 'PUBLIC') NOT NULL DEFAULT 'MEMBERS_AND_ABOVE' COMMENT '告知対象ロール（MEMBERS_AND_ABOVE=内輪・応援者除外）',
    target_team_ids   JSON             NULL COMMENT '組織告知でのチーム絞り込み。NULL=全チーム対象。[1,3,5] のように team.id の配列',
    preferred_channel VARCHAR(30)      NULL COMMENT '優先チャネル: BULLETIN_THREAD / TIMELINE_POST / BLOG_POST / TODO / SCHEDULE / SURVEY',
    is_default        BOOLEAN          NOT NULL DEFAULT FALSE COMMENT 'スコープごとのデフォルトテンプレートフラグ（1スコープあたり最大1件）',
    created_by        BIGINT UNSIGNED  NULL COMMENT 'FK → users(id) ON DELETE SET NULL',
    created_at        DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_art_scope (scope_type, scope_id),
    INDEX idx_art_created_by (created_by),
    CONSTRAINT fk_art_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
