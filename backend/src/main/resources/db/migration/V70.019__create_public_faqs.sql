-- F21.1 GEO最適化 Phase 2 §5.5: FAQ駆動GEO 基盤テーブル
--
-- 公開FAQ（チーム/組織）。固定6問（question_key 非NULL）と自由質問（question_text 非NULL）を
-- 1表で管理する。回答済みのもののみ FAQPage JSON-LD として出力し、GEO（生成AI検索）での
-- 引用率向上を狙う。
--
-- 設計原則（CLAUDE.md準拠）:
--   - 原則6: 新規テーブルの主キーは UUIDv7（BINARY(16)）
--   - 原則1: クロスドメインFK禁止 → created_by は users.id を参照するが FK は張らず index のみ
--   - scope_id（teams.id / organizations.id）も同様に FK なし・アプリ層で整合性保証
--   - 論理削除（deleted_at）に対応
CREATE TABLE public_faqs (
  id            BINARY(16)      NOT NULL COMMENT 'UUIDv7',
  scope_type    VARCHAR(20)     NOT NULL COMMENT 'TEAM / ORGANIZATION',
  scope_id      BIGINT UNSIGNED NOT NULL COMMENT 'チーム/組織ID（FKなし・アプリ層整合）',
  question_key  VARCHAR(40)     NULL COMMENT '固定質問キー。自由質問はNULL',
  question_text VARCHAR(255)    NULL COMMENT '自由質問の質問文。固定はNULL（i18n描画）',
  answer_text   TEXT            NOT NULL COMMENT '回答本文',
  display_order INT UNSIGNED    NOT NULL DEFAULT 0,
  created_by    BIGINT UNSIGNED NULL COMMENT 'users.id（FKなし・indexのみ）',
  created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at    DATETIME        NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_public_faqs_fixed (scope_type, scope_id, question_key),
  INDEX idx_public_faqs_scope (scope_type, scope_id, deleted_at, display_order),
  INDEX idx_public_faqs_created_by (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公開FAQ（チーム/組織）';
