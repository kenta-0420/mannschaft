-- F19.1 Phase 2: 投稿者識別モード変更履歴テーブル（監査・非対称切替ルール検証用）
CREATE TABLE team_name_disclosure_change_logs (
    id            BINARY(16)                          NOT NULL,
    team_id       BIGINT                              NOT NULL,
    changed_by    BIGINT                              NOT NULL,
    old_mode      ENUM('DISPLAY_NAME', 'REAL_NAME')   NOT NULL,
    new_mode      ENUM('DISPLAY_NAME', 'REAL_NAME')   NOT NULL,
    confirmed     BOOLEAN                             NOT NULL DEFAULT FALSE,
    changed_at    DATETIME(6)                         NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tndcl_team_id (team_id),
    INDEX idx_tndcl_changed_at (changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE organization_name_disclosure_change_logs (
    id              BINARY(16)                          NOT NULL,
    organization_id BIGINT                              NOT NULL,
    changed_by      BIGINT                              NOT NULL,
    old_mode        ENUM('DISPLAY_NAME', 'REAL_NAME')   NOT NULL,
    new_mode        ENUM('DISPLAY_NAME', 'REAL_NAME')   NOT NULL,
    confirmed       BOOLEAN                             NOT NULL DEFAULT FALSE,
    changed_at      DATETIME(6)                         NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_ondcl_org_id (organization_id),
    INDEX idx_ondcl_changed_at (changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
