-- F01.2.1 組織のチームグループ 部隊 1-B（設計書 §5.6 順7）:
--   announcement_feed_group_snapshots / notification_fanout_audiences / notification_fanout_audience_teams
-- notification_fanout_jobs には列を足さない（宛先集合のキーは scope_ref に UUID 文字列で入れる。設計書 §5.6）。
-- クロスドメイン FK は張らない（原則1）。CASCADE は同一ドメイン内のみ（原則2）。
--
-- 【設計書 §5.6 の DDL との差分（原則6「新規テーブルは UuidV7Entity 継承」を満たすための調整）】
--   子表2つ（snapshots / audience_teams）は複合主キーではなく id BINARY(16) を主キーにし、
--   設計書の複合主キーは UNIQUE に置き換える（一意性・検索経路は同じ）。
--   見出し表の主キー列は設計書どおり audience_snapshot_id（フィード ID から決定的に導く UUID。Entity 側は id 列を上書き）。
--   created_at は Instant で持つため DATETIME(6) とする。

-- グループ宛てお知らせの送信時スナップショット（グループ削除後の表示判定に使う。F01.2.1 §8.2）
CREATE TABLE announcement_feed_group_snapshots (
    id       BINARY(16)      NOT NULL,
    feed_id  BIGINT UNSIGNED NOT NULL COMMENT 'announcement_feeds.id（同一ドメイン）',
    group_id CHAR(36)        NOT NULL COMMENT '送信時に展開したチームグループID（UUID文字列・クロスドメインFKなし）',
    team_id  BIGINT UNSIGNED NOT NULL COMMENT '送信時点でそのグループに ACTIVE で所属していたチームID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_afgs_feed_group_team (feed_id, group_id, team_id),
    INDEX idx_afgs_team_feed (team_id, feed_id),
    CONSTRAINT fk_afgs_feed FOREIGN KEY (feed_id) REFERENCES announcement_feeds (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='グループ宛てお知らせの送信時の対象チーム（グループ単位）';

-- fan-out の宛先集合の見出し（ORGANIZATION_TEAMS 戦略用）。ジョブ行の scope_ref がこの audience_snapshot_id を指す
CREATE TABLE notification_fanout_audiences (
    audience_snapshot_id BINARY(16)      NOT NULL COMMENT '宛先集合のID（送信時に確定。ジョブ行の scope_ref に UUID 文字列で入る）',
    organization_id      BIGINT UNSIGNED NOT NULL COMMENT '宛先の組織ID（直属メンバーの解決と加盟の再確認に使う。クロスドメインFKなし）',
    created_at           DATETIME(6)     NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (audience_snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='ORGANIZATION_TEAMS fan-out の宛先集合の見出し（送信時点で固定）';

-- 宛先集合の中身（宛先チーム）。0行でもよい（直属メンバーだけに届く告知）
CREATE TABLE notification_fanout_audience_teams (
    id                   BINARY(16)      NOT NULL,
    audience_snapshot_id BINARY(16)      NOT NULL COMMENT '宛先集合のID（notification_fanout_audiences と同一ドメイン）',
    team_id              BIGINT UNSIGNED NOT NULL COMMENT '宛先チームID（クロスドメインFKなし）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_nfat_audience_team (audience_snapshot_id, team_id),
    CONSTRAINT fk_nfat_audience FOREIGN KEY (audience_snapshot_id)
        REFERENCES notification_fanout_audiences (audience_snapshot_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='ORGANIZATION_TEAMS fan-out の宛先チーム集合（送信時点で固定）';
