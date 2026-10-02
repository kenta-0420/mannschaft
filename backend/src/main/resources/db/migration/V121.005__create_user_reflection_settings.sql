-- F06.5 アクティブリコール学習機能: user_reflection_settings（ユーザー通知設定）
-- 想起通知の時刻をユーザーごとに設定（夜学習対応）。user_blog_settings を手本に自然キー。
-- UuidV7 を適用しない例外（原則6例外）: ユーザーごと1行のシングルトン的設定ゆえ user_id 自然キー。
CREATE TABLE user_reflection_settings (
    user_id      BIGINT      NOT NULL,                        -- 所有者（users ドメイン・FK なし・自然キー）
    remind_hour  TINYINT     NOT NULL DEFAULT 8,              -- 想起通知の時刻（0-23・ユーザーTZ基準）
    created_at   DATETIME    NOT NULL,
    updated_at   DATETIME    NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT chk_user_reflection_settings_hour CHECK (remind_hour BETWEEN 0 AND 23)
);
