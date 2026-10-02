-- F06.5 アクティブリコール学習機能: reflection_entries（日々の振り返り）
-- (theme_id, target_date) 一意で「1テーマ×1日＝1エントリ」を保証。同日再保存は upsert。
-- theme_id は同一 reflection ドメインゆえ FK＋CASCADE 可。user_id 等の他ドメイン参照は FK なし。
CREATE TABLE reflection_entries (
    id                  BINARY(16)   NOT NULL,
    theme_id            BINARY(16)   NOT NULL,                       -- 同一ドメイン FK 可
    user_id             BIGINT       NOT NULL,                       -- 所有者非正規化（高速絞り込み・FK なし）
    target_date         DATE         NOT NULL,                       -- 振り返り対象日
    structured_content  JSON         NOT NULL,                       -- アウトライン構造（§2.3）
    visibility          VARCHAR(20)  NOT NULL DEFAULT 'PRIVATE',     -- MVPはPRIVATE固定（§6.1/§9.1）
    version             BIGINT       NOT NULL DEFAULT 0,             -- 楽観ロック（@Version・AC-18）
    exported_blog_post_id BIGINT     NULL,                           -- 輸出先ブログ記事ID（cmsドメイン・FKなし・再輸出防止/輸出済表示・AC-20）
    created_at          DATETIME     NOT NULL,
    updated_at          DATETIME     NOT NULL,
    deleted_at          DATETIME     NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_reflection_entries_theme
        FOREIGN KEY (theme_id) REFERENCES reflection_themes(id) ON DELETE CASCADE,  -- 同一ドメイン
    CONSTRAINT chk_reflection_entries_visibility
        CHECK (visibility = 'PRIVATE'),  -- MVP。別軍議でFAMILY_SHARED追加
    CONSTRAINT uq_reflection_entries_theme_date
        UNIQUE (theme_id, target_date),                             -- (theme,target_date) 一意（AC-4）
    INDEX idx_reflection_entries_user_date (user_id, target_date),
    INDEX idx_reflection_entries_theme (theme_id, target_date)
);
