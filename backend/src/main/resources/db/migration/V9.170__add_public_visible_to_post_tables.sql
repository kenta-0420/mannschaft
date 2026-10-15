-- F19.1 Phase 2: 投稿の公開表示フラグを追加（管理者が個別投稿を非公開化できる予備カラム）
ALTER TABLE blog_posts
    ADD COLUMN public_visible BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE timeline_posts
    ADD COLUMN public_visible BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE events
    ADD COLUMN public_visible BOOLEAN NOT NULL DEFAULT TRUE;
