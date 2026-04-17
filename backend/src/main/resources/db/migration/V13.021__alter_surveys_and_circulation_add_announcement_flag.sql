-- F02.6 お知らせウィジェット: surveys / circulation_documents へお知らせ自動登録フラグ追加
-- surveys: 公開時に自動でお知らせウィジェットに登録するフラグ
-- circulation_documents: 配信開始時に自動でお知らせウィジェットに登録するフラグ

-- アンケート: 公開時にお知らせウィジェットへ自動登録するフラグ
ALTER TABLE surveys
    ADD COLUMN post_announcement_on_publish BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT '公開時に自動でお知らせウィジェットに登録する'
    AFTER updated_at;

-- 回覧板: 配信開始時にお知らせウィジェットへ自動登録するフラグ
ALTER TABLE circulation_documents
    ADD COLUMN post_announcement_on_start BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT '配信開始時に自動でお知らせウィジェットに登録する'
    AFTER updated_at;
