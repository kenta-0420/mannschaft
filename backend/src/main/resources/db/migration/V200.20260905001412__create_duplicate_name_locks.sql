-- CMP-260901-1538 柱③-A 検分第3巡是正: 組織・チーム名称の同名確認フローにおける
-- TOCTOU 対策を、MySQL 名前付きアドバイザリロック（GET_LOCK/RELEASE_LOCK）方式から
-- 「ロック専用テーブルの行ロック」方式へ転換する（GET_LOCK 方式は解放タイミングと
-- 接続管理の構造問題が消えないため廃止）。
--
-- 設計: 組織名・チーム名は一意制約を持たない（同名の併存を許可する設計のため、
-- 通常の UNIQUE 制約では TOCTOU を防げない）。そこで「正規化名ごとに必ず1行だけ存在する」
-- ロック専用の行を用意し、INSERT ... ON DUPLICATE KEY UPDATE で複合PKに対する
-- X ロック（排他ロック）を取得する。このロックは呼び出し元と**同一トランザクション**内で
-- 取得し、明示的な解放処理は一切書かない。InnoDB は commit/rollback のどちらでも
-- そのトランザクションが保持する行ロックを自動的に解放するため、解放漏れが原理的に無い
-- （GET_LOCK 方式で問題になった「専用接続の管理」「afterCompletion のタイミング」
-- 「RELEASE_LOCK 失敗時のプール残留」がすべて構造的に消える）。
--
-- name_key はロック対象の識別子（scope_kind + 正規化名（trim + lowercase 等）を
-- SHA-256 でハッシュ化した hex 文字列）。実データを持たず、行の存在自体がロック対象を
-- 表す「実質的に恒久行」のテーブルのため、他テーブルへの参照（FK）は持たない。
--
-- UuidV7Entity 適用除外の判断（docs/architecture/domain_db_design_principles.md 原則6）:
-- 本テーブルはテナント・ユーザーごとに行が増える通常のドメインテーブルではなく、
-- 「正規化名の種類数」分だけ恒久的に存在するロック専用の管理データであり、
-- シャーディング時は（マスタテーブルと同様に）全シャードへ同じ行をコピーする運用が自然で、
-- 原則6の意図（将来シャーディング時の各ノード独立発番）に該当しない。よって
-- マスタ例外に準じて自然キー（複合主キー）のまま設計する。
CREATE TABLE duplicate_name_locks (
    scope_kind  VARCHAR(32)  NOT NULL COMMENT 'DuplicateNameScopeKind（ORGANIZATION/TEAM）',
    name_key    VARCHAR(64)  NOT NULL COMMENT 'scope_kind+正規化名(trim+lowercase等)のSHA-256 hex',
    created_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (scope_kind, name_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='柱③-A 同名確認フロー用の行ロック専用テーブル（実データなし・FKなし）';
