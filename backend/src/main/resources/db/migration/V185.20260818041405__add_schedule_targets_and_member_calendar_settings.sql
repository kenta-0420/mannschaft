-- CMP-051: 予定対象者とスコープ別メンバー色
-- schedule_id は schedule ドメイン内参照のため FK/CASCADE を許可する。
-- user_id / scope_id はクロスドメイン参照であり FK を張らない。

ALTER TABLE schedules
    ADD COLUMN target_mode VARCHAR(20) NOT NULL DEFAULT 'ALL_MEMBERS'
        COMMENT 'ALL_MEMBERS または SELECTED_MEMBERS';

CREATE TABLE schedule_targets (
    id          BINARY(16)      NOT NULL COMMENT 'UUIDv7',
    schedule_id BIGINT UNSIGNED NOT NULL,
    user_id     BIGINT UNSIGNED NOT NULL COMMENT 'users への論理参照。FK禁止',
    PRIMARY KEY (id),
    CONSTRAINT uq_schedule_targets_schedule_user UNIQUE (schedule_id, user_id),
    INDEX idx_schedule_targets_user_schedule (user_id, schedule_id),
    CONSTRAINT fk_schedule_targets_schedule
        FOREIGN KEY (schedule_id) REFERENCES schedules(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='CMP-051 共有予定の明示対象者';

CREATE TABLE scope_member_calendar_settings (
    id             BINARY(16)      NOT NULL COMMENT 'UUIDv7',
    scope_type     VARCHAR(12)     NOT NULL,
    scope_id       BIGINT UNSIGNED NOT NULL COMMENT 'team/organization への論理参照。FK禁止',
    user_id        BIGINT UNSIGNED NOT NULL COMMENT 'users への論理参照。FK禁止',
    calendar_color CHAR(7)         NOT NULL COMMENT '#RRGGBB',
    PRIMARY KEY (id),
    CONSTRAINT uq_scope_member_calendar_settings_scope_user UNIQUE (scope_type, scope_id, user_id),
    INDEX idx_scope_member_calendar_settings_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='CMP-051 スコープ別メンバーのカレンダー表示色';
