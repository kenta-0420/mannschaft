-- F19.1 Phase 2: 投稿時の本名スナップショット列を追加（投稿者が REAL_NAME モードの組織/チームに所属する場合のみ格納）
ALTER TABLE blog_posts
    ADD COLUMN author_real_name_snapshot VARCHAR(100) NULL AFTER author_id;

ALTER TABLE timeline_posts
    ADD COLUMN author_real_name_snapshot VARCHAR(100) NULL;

ALTER TABLE events
    ADD COLUMN author_real_name_snapshot VARCHAR(100) NULL;
