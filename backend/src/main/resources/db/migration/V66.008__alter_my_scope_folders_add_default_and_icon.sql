-- F15.3 マイスコープフォルダ統合UX - DBスキーマ拡張
--
-- 設計書: docs/features/F15.3_scope_folder_integration.md §4.2
--
-- 変更内容:
--   1. my_scope_folders に is_default / icon カラム追加
--   2. user_id × scope_type ごとに is_default=TRUE は 1 行のみとする一意制約
--      （MySQL は部分インデックスを直接サポートしないため、STORED 生成列 +
--       NULL を許容する UNIQUE 制約で同等の効果を実現する。
--       設計書 §4.2 の PostgreSQL 構文を MySQL 向けに翻訳）
--   3. my_scope_folder_items に assigned_via 監査カラム追加

ALTER TABLE my_scope_folders
  ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE COMMENT '未分類フォルダフラグ。user_id × scope_type ごとに 1 行のみ',
  ADD COLUMN icon VARCHAR(40) NULL COMMENT 'PrimeIcons の pi-icon 名（例: pi-briefcase）';

-- user_id × scope_type ごとに「未分類（is_default=TRUE）」は 1 行のみ。
-- 論理削除済みも対象外。is_default=FALSE 行は default_uniq_key=NULL となり、
-- MySQL では NULL が複数行ユニーク制約に許容されるため衝突しない。
ALTER TABLE my_scope_folders
  ADD COLUMN default_uniq_key VARCHAR(80)
    GENERATED ALWAYS AS (
      CASE
        WHEN is_default = TRUE AND deleted_at IS NULL
          THEN CONCAT('default:', user_id, ':', scope_type)
        ELSE NULL
      END
    ) STORED,
  ADD CONSTRAINT uq_msf_user_scope_default UNIQUE (default_uniq_key);

-- アイテムにどの経路で割り当てられたかの監査用カラム
-- 値: INVITE / MANUAL / MIGRATION / DEFAULT
ALTER TABLE my_scope_folder_items
  ADD COLUMN assigned_via VARCHAR(20) NOT NULL DEFAULT 'MANUAL'
    COMMENT 'アイテム割当経路。INVITE / MANUAL / MIGRATION / DEFAULT';
