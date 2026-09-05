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
-- 検分第4巡是正: name_key は当初 SHA-256 ハッシュ文字列だったが、Java 側で
-- MySQL の utf8mb4_0900_ai_ci 照合（大文字小文字・アクセントを区別しない）を
-- 再現するのは不可能なため、trim 済みの「生の名称」をそのまま格納する方式へ変更した。
-- 本テーブル自体が utf8mb4_0900_ai_ci で作成されているため、PRIMARY KEY の等価比較
-- （INSERT ... ON DUPLICATE KEY UPDATE の重複検知）が候補検索
-- （organizations/teams の name_trimmed = TRIM(?) 比較）と同じ照合順序で行われ、
-- 「Foo」と「foo」が同一ロック行に衝突するようになる（同名判定とロック対象が完全一致）。
-- 列長は organizations.name / teams.name と同じ VARCHAR(100) に合わせる。
--
-- name_key は実データを持たず、行の存在自体がロック対象を表す「実質的に恒久行」の
-- テーブルのため、他テーブルへの参照（FK）は持たない。
--
-- UuidV7Entity 適用除外の判断（docs/architecture/domain_db_design_principles.md 原則6）:
-- 本テーブルはテナント・ユーザーごとに行が増える通常のドメインテーブルではなく、
-- 「正規化名の種類数」分だけ恒久的に存在するロック専用の管理データであり、
-- シャーディング時は（マスタテーブルと同様に）全シャードへ同じ行をコピーする運用が自然で、
-- 原則6の意図（将来シャーディング時の各ノード独立発番）に該当しない。よって
-- マスタ例外に準じて自然キー（複合主キー）のまま設計する。
CREATE TABLE duplicate_name_locks (
    scope_kind  VARCHAR(32)  NOT NULL COMMENT 'DuplicateNameScopeKind（ORGANIZATION/TEAM）',
    name_key    VARCHAR(100) NOT NULL COMMENT 'trim済みの生の名称（organizations/teams.nameと同じ列長）。'
                                              'テーブルのutf8mb4_0900_ai_ci照合により候補検索と同じ同名判定になる',
    created_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (scope_kind, name_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='柱③-A 同名確認フロー用の行ロック専用テーブル（実データなし・FKなし）';
