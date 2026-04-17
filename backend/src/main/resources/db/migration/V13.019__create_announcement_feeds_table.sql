-- F02.6 お知らせウィジェット: announcement_feeds テーブル作成
-- 複数機能（ブログ・掲示板・タイムライン・回覧板・アンケート）の投稿を横断集約するポリモルフィックフィード
CREATE TABLE announcement_feeds (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type        VARCHAR(20)     NOT NULL COMMENT '表示スコープ種別: TEAM / ORGANIZATION',
    scope_id          BIGINT UNSIGNED NOT NULL COMMENT '表示スコープのID（teams.id / organizations.id）',
    source_type       VARCHAR(30)     NOT NULL COMMENT '元コンテンツ種別: BLOG_POST / BULLETIN_THREAD / TIMELINE_POST / CIRCULATION_DOCUMENT / SURVEY',
    source_id         BIGINT UNSIGNED NOT NULL COMMENT '元コンテンツのID（ポリモルフィック参照）',
    author_id         BIGINT UNSIGNED NULL     COMMENT 'お知らせ表示フラグを付けた操作者（退会時 NULL）',
    title_cache       VARCHAR(200)    NOT NULL  COMMENT '表示用タイトル（元コンテンツから非正規化）',
    excerpt_cache     VARCHAR(300)    NULL      COMMENT '本文抜粋（非正規化、リスト表示用）',
    priority          VARCHAR(20)     NOT NULL DEFAULT 'NORMAL' COMMENT 'お知らせ優先度: URGENT / IMPORTANT / NORMAL',
    is_pinned         BOOLEAN         NOT NULL DEFAULT FALSE COMMENT 'ピン留めフラグ（ADMIN のみ変更可、スコープごと最大5件）',
    pinned_at         DATETIME        NULL      COMMENT 'ピン留め操作日時',
    pinned_by         BIGINT UNSIGNED NULL      COMMENT 'ピン留めした ADMIN のユーザーID（退会時 NULL）',
    visibility        VARCHAR(30)     NOT NULL DEFAULT 'MEMBERS_ONLY' COMMENT '閲覧可能範囲（元コンテンツから継承・同期）',
    starts_at         DATETIME        NULL      COMMENT '表示開始日時（NULL = 即時）',
    expires_at        DATETIME        NULL      COMMENT '表示終了日時（NULL = 期限なし）',
    source_deleted_at DATETIME        NULL      COMMENT '元コンテンツ削除検出日時（ApplicationEvent 経由でセット。90日後バッチで物理削除）',
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    -- 同一コンテンツの重複お知らせ化を防ぐ複合ユニーク制約
    UNIQUE KEY uq_af_scope_source (scope_type, scope_id, source_type, source_id),

    -- ウィジェット取得の主要クエリ用インデックス（スコープ絞り込み + ピン留め優先 + 新着順）
    INDEX idx_af_scope_feed (scope_type, scope_id, is_pinned DESC, created_at DESC)
        COMMENT 'ウィジェット一覧取得の主要クエリ用',

    -- 期限切れバッチ処理用
    INDEX idx_af_expires (expires_at)
        COMMENT '期限切れバッチ処理用',

    -- ソース削除バッチ・通報調査用の逆引き
    INDEX idx_af_source (source_type, source_id)
        COMMENT '元コンテンツからの逆引き・削除連動用',

    -- 著者別（自分のお知らせ一覧・退会処理用）
    INDEX idx_af_author (author_id)
        COMMENT '著者別一覧・退会処理用',

    -- author_id への外部キー（退会時は NULL に設定）
    CONSTRAINT fk_af_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE SET NULL

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='お知らせウィジェット横断フィード。投稿者が「お知らせ表示」を ON にしたコンテンツを横断集約するポリモルフィックテーブル';
