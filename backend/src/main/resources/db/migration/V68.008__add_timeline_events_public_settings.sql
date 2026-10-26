-- F19.1 Phase 7: タイムライン投稿・イベントの公開設定をチーム/組織単位で制御するカラム追加
-- マスター裁可 2026-05-23
ALTER TABLE teams
    ADD COLUMN timeline_posts_public BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'タイムライン投稿を公開ページに表示するか（FALSE=非公開、管理者が明示的に有効化）';

ALTER TABLE organizations
    ADD COLUMN timeline_posts_public BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'タイムライン投稿を公開ページに表示するか',
    ADD COLUMN public_events_enabled BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'イベントを公開ページに表示するか（当初「常時公開」としていたが 2026-05-23 方針変更）';
