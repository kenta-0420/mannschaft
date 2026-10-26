-- F19.1 Phase 6-B: 公開投稿へのコメントテーブル
-- 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.7 Phase 6-B
-- ログイン済みユーザーが public_visible=true の BlogPost にコメントを投稿できる
CREATE TABLE public_post_comments (
  id          CHAR(36)   NOT NULL COMMENT 'UUIDv7',
  post_id     BIGINT     NOT NULL COMMENT '対象 BlogPost の ID',
  author_id   BIGINT     NOT NULL COMMENT '投稿者ユーザー ID（users.id）',
  content     TEXT       NOT NULL COMMENT 'コメント本文（最大 1000 文字）',
  author_real_name_snapshot VARCHAR(100) DEFAULT NULL
                          COMMENT '投稿者本名スナップショット（REAL_NAME モード時のみ設定）',
  created_at  DATETIME(6) NOT NULL,
  deleted_at  DATETIME(6) DEFAULT NULL,
  PRIMARY KEY (id),
  INDEX idx_ppcomments_post (post_id, created_at),
  INDEX idx_ppcomments_author (author_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
