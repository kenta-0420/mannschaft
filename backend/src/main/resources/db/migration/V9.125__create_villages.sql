-- F17.1 Phase 1: 村本体テーブル
-- 「袖振り合うも他生の縁」の横断コミュニティ。組織・チーム・個人の垣根を越える。
-- 原則6 適用: PK = UUIDv7 BINARY(16)
-- 原則7 適用外: 全テナント横断ゆえ organization_id を持たない

CREATE TABLE villages (
    id                  BINARY(16)    NOT NULL                                       COMMENT 'UUIDv7 PK',
    slug                VARCHAR(64)   NOT NULL                                       COMMENT 'URL スラッグ（半角英数ハイフン）',
    name                VARCHAR(100)  NOT NULL                                       COMMENT '村名（表示用）',
    description         TEXT          NULL                                           COMMENT '村の紹介文',
    type                VARCHAR(20)   NOT NULL DEFAULT 'COMMUNITY'                   COMMENT '公式村 / 任意村 (OFFICIAL/COMMUNITY)',
    join_policy         VARCHAR(20)   NOT NULL DEFAULT 'FREE'                        COMMENT '参加方式 (FREE/APPROVAL)',
    visibility          VARCHAR(20)   NOT NULL DEFAULT 'PUBLIC'                      COMMENT '検索可否 (PUBLIC/UNLISTED)',
    category            VARCHAR(64)   NULL                                           COMMENT 'カテゴリ（業種/地域/趣味 等）',
    icon_r2_key         VARCHAR(255)  NULL                                           COMMENT 'R2 上のアイコンキー',
    cover_r2_key        VARCHAR(255)  NULL                                           COMMENT 'カバー画像 R2 キー',
    monsho_r2_key       VARCHAR(255)  NULL                                           COMMENT 'Phase 2: 村紋 R2 キー',
    guideline_md        MEDIUMTEXT    NULL                                           COMMENT '村ガイドライン Markdown',
    member_count_cache  BIGINT UNSIGNED NOT NULL DEFAULT 0                           COMMENT 'メンバー数キャッシュ',
    created_by_user_id  BIGINT UNSIGNED NULL                                         COMMENT '作成者（FK 張らない / 退会時 NULL 化）',
    created_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at          DATETIME(6)   NULL                                           COMMENT '論理削除（村長判断）',
    archived_at         DATETIME(6)   NULL                                           COMMENT '永久凍結（運営判断・違反村）',
    version             BIGINT        NOT NULL DEFAULT 0                             COMMENT '楽観ロック',
    PRIMARY KEY (id),
    UNIQUE KEY uk_villages_slug (slug),
    UNIQUE KEY uk_villages_name (name),
    KEY idx_villages_type (type),
    KEY idx_villages_visibility_deleted (visibility, deleted_at),
    KEY idx_villages_category (category),
    KEY idx_villages_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='村本体（F17.1）';
