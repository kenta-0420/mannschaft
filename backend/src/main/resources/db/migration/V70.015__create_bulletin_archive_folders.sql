-- F05.1 掲示板: 保管庫（アーカイブ）フォルダテーブルを作成する。
-- 設計書: docs/features/F05.1_bulletin_board.md §3 bulletin_archive_folders / §5 / §7
--
-- スコープ（チーム/組織）全員で共有する保管庫フォルダ。管理者（ADMIN/DEPUTY_ADMIN）が整理し、
-- ネスト（多階層・最大5階層 = depth 0〜4）に対応する。隣接リスト + depth カラム方式。
--
-- CLAUDE.md 原則6: 新規テーブルのため主キーは UUIDv7（BINARY(16)）。
--   テナント（スコープ）ごとに行が増えるテーブルでありマスタ例外・シングルトン例外に該当しないため適用。
-- bulletin 慣習（FK 最小・参照整合性はアプリ層で保証）:
--   - scope_type / scope_id への FK なし（scope_type で参照先が変わるため。アプリ層検証）
--   - parent_folder_id は同テーブル内の自己参照だが FK 制約なし（bulletin_replies.parent_id と同方針）
--   - created_by のみ users への FK（ON DELETE SET NULL。退会してもフォルダは残す）= 唯一の FK
CREATE TABLE bulletin_archive_folders (
  id                BINARY(16)        NOT NULL                  COMMENT 'UUIDv7 主キー',
  scope_type        VARCHAR(20)       NOT NULL                  COMMENT 'スコープ種別 TEAM / ORGANIZATION（既存 bulletin 慣習で VARCHAR）',
  scope_id          BIGINT UNSIGNED   NOT NULL                  COMMENT 'チーム/組織の ID（FK なし・アプリ層整合）',
  scope_village_id  BINARY(16)        NULL                      COMMENT '村スコープ時の村 ID（将来対応用。当面 UI は team/org のみ）',
  parent_folder_id  BINARY(16)        NULL                      COMMENT '親フォルダ（自己参照ネスト）。NULL = 保管庫直下のルートフォルダ。FK なし',
  name              VARCHAR(100)      NOT NULL                  COMMENT 'フォルダ名',
  color             VARCHAR(7)        NULL                      COMMENT 'フォルダカラー（HEX 形式 #FF5733）',
  icon              VARCHAR(40)       NULL                      COMMENT 'アイコン（PrimeIcons 名 例 pi-folder）',
  depth             TINYINT UNSIGNED  NOT NULL DEFAULT 0        COMMENT '階層の深さ。ルート = 0、最大4（= 5 階層）',
  display_order     INT UNSIGNED      NOT NULL DEFAULT 0        COMMENT '同一親の中での表示順',
  created_by        BIGINT UNSIGNED   NULL                      COMMENT 'FK → users（作成者）。ON DELETE SET NULL（退会時にフォルダは残す）',
  created_at        DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at        DATETIME          NULL                      COMMENT '論理削除日時',
  PRIMARY KEY (id),
  -- スコープ別・親別の子フォルダ一覧（ツリー構築の主クエリ）
  INDEX idx_bulletin_archive_folders_scope_parent (scope_type, scope_id, parent_folder_id, display_order),
  -- 親フォルダ単独の逆引き（移動・削除時の子取得）
  INDEX idx_bulletin_archive_folders_parent (parent_folder_id),
  -- created_by の唯一の FK（ユーザー退会時にフォルダは残す）
  CONSTRAINT fk_bulletin_archive_folders_created_by
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='掲示板 保管庫（アーカイブ）フォルダ';
