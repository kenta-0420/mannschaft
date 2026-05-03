-- F03.15 個人時間割: ユーザーごと設定（1ユーザー1行・UPSERT 動作）
CREATE TABLE IF NOT EXISTS personal_timetable_settings (
    user_id                                  BIGINT UNSIGNED NOT NULL                COMMENT 'PK + FK → users.id',
    auto_reflect_class_changes_to_calendar   BOOLEAN         NOT NULL DEFAULT TRUE   COMMENT 'チーム時間割の臨時変更を個人カレンダー自動反映',
    notify_team_slot_note_updates            BOOLEAN         NOT NULL DEFAULT TRUE   COMMENT 'チーム時間割コマ共通メモ更新の通知を受け取る',
    default_period_template                  VARCHAR(20)     NOT NULL DEFAULT 'CUSTOM' COMMENT 'デフォルト時限テンプレート',
    visible_default_fields                   JSON            NOT NULL                COMMENT 'メモエディタ表示項目 ["preparation","review","items_to_bring","free_memo"]',
    created_at                               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (user_id),

    CONSTRAINT fk_pts_settings_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_pts_settings_template CHECK (default_period_template IN ('ELEMENTARY','JUNIOR_HIGH','HIGH_SCHOOL','UNIV_90MIN','UNIV_100MIN','CUSTOM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='F03.15 個人時間割ユーザー設定';
