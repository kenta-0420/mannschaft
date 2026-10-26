-- F08.3: 議決権行使・委任状 — 投票セッションテーブル
CREATE TABLE proxy_vote_sessions (
    id                          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    scope_type                  VARCHAR(20)      NOT NULL COMMENT 'スコープ（TEAM / ORGANIZATION）',
    team_id                     BIGINT UNSIGNED  NULL     COMMENT 'FK → teams。TEAM の場合に設定',
    organization_id             BIGINT UNSIGNED  NULL     COMMENT 'FK → organizations。ORGANIZATION の場合に設定',
    title                       VARCHAR(200)     NOT NULL COMMENT 'セッション名',
    description                 TEXT             NULL     COMMENT 'セッション概要・議事要旨',
    resolution_mode             VARCHAR(20)      NOT NULL COMMENT '決議モード（MEETING / WRITTEN）',
    status                      VARCHAR(20)      NOT NULL DEFAULT 'DRAFT' COMMENT 'ステータス（DRAFT / OPEN / CLOSED / FINALIZED）',
    meeting_date                DATE             NULL     COMMENT '総会開催日。MEETING モードの場合に設定',
    voting_start_at             DATETIME         NULL     COMMENT '投票受付開始日時',
    voting_end_at               DATETIME         NULL     COMMENT '投票受付締切日時',
    is_anonymous                BOOLEAN          NOT NULL DEFAULT FALSE COMMENT '無記名投票フラグ',
    quorum_type                 VARCHAR(20)      NOT NULL DEFAULT 'MAJORITY' COMMENT '定足数算出方式（MAJORITY / TWO_THIRDS / CUSTOM）',
    quorum_threshold            DECIMAL(5,2)     NULL     COMMENT 'カスタム定足数の閾値（%）',
    eligible_count              INT UNSIGNED     NOT NULL COMMENT '議決権総数（スナップショット）',
    is_auto_accept_delegation   BOOLEAN          NOT NULL DEFAULT FALSE COMMENT '委任状の自動承認',
    remind_before_hours         JSON             NULL     COMMENT '自動リマインドスケジュール（配列。例: [72, 24]）',
    last_remind_hour            INT              NULL     COMMENT '最後に通知した時間ポイント',
    created_by                  BIGINT UNSIGNED  NOT NULL COMMENT 'FK → users。作成者',
    version                     BIGINT           NOT NULL DEFAULT 0 COMMENT '楽観的ロックバージョン',
    created_at                  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at                  DATETIME         NULL     COMMENT '論理削除日時',

    PRIMARY KEY (id),
    INDEX idx_pvs_scope (scope_type, team_id, organization_id, status),
    INDEX idx_pvs_status (status, voting_end_at),

    CONSTRAINT fk_pvs_team        FOREIGN KEY (team_id)         REFERENCES teams(id),
    CONSTRAINT fk_pvs_organization FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_pvs_created_by  FOREIGN KEY (created_by)      REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.3 投票セッション';
