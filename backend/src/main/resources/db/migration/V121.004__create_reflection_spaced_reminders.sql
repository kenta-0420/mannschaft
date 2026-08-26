-- F06.5 アクティブリコール学習機能: reflection_spaced_reminders（間隔反復スケジュール）
-- バッチ走査テーブル。entry_id（SPACED）/ theme_id（PRE_EXAM）の多態のため ID 参照（FK なし・§2.5）。
-- 孤児はバッチ側で「親不在ならスキップ＆CANCELLED 化」で fail-safe 処理する。
CREATE TABLE reflection_spaced_reminders (
    id            BINARY(16)  NOT NULL,
    entry_id      BINARY(16)  NULL,                                 -- 振り返りエントリID（SPACED 時必須・PRE_EXAM 時 NULL 可）
    theme_id      BINARY(16)  NULL,                                 -- テーマID（PRE_EXAM 総まとめ時に使用・FK なし）
    user_id       BIGINT      NOT NULL,                             -- 受信者（FK なし）
    remind_at     DATETIME    NOT NULL,                             -- 絶対日時（ユーザーTZでの予定時刻を JST 保存・§5.3）
    interval_days INT         NULL,                                 -- SPACED: 1/3/7/14、PRE_EXAM: 14/7/3/1（考査N日前）
    kind          VARCHAR(12) NOT NULL,                             -- SPACED/PRE_EXAM
    status        VARCHAR(12) NOT NULL DEFAULT 'PENDING',           -- PENDING/SENT/CANCELLED
    sent_at       DATETIME    NULL,
    created_at    DATETIME    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_reflection_spaced_reminders_kind
        CHECK (kind IN ('SPACED','PRE_EXAM')),
    CONSTRAINT chk_reflection_spaced_reminders_status
        CHECK (status IN ('PENDING','SENT','CANCELLED')),
    INDEX idx_reflection_spaced_reminders_due (status, remind_at),  -- バッチ走査の主キー
    INDEX idx_reflection_spaced_reminders_entry (entry_id),
    INDEX idx_reflection_spaced_reminders_theme (theme_id, kind),
    INDEX idx_reflection_spaced_reminders_user (user_id)
);
