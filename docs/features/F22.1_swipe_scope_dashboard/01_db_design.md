# F22.1: DB設計

> **ステータス**: 🟢 設計確定
> **最終更新**: 2026-05-30
> **関連ドキュメント**:
> - [README.md](./README.md) — 機能概要
> - [02_api_design.md](./02_api_design.md) — API 仕様
> - [F02.2_dashboard.md](../F02.2_dashboard.md) — `dashboard_widget_settings`（近縁テーブル）
> - [F15.3_scope_folder_integration.md](../F15.3_scope_folder_integration.md) — `my_scope_folders` / `my_scope_folder_items`（読み取り流用）
> - [CLAUDE.md](../../../CLAUDE.md) — アーキテクチャ思想（DB 設計原則 1〜7）

---

## 1. テーブル一覧

| テーブル名 | 主キー | 用途 | 論理削除 |
|-----------|-------|------|---------|
| `dashboard_scope_tab_order` | `id`（UUIDv7 / BINARY(16)）| ユーザーごとのチーム/組織タグの表示順 | なし（物理削除） |

**既存テーブル（他フェーズで作成済み）の参照**:

| テーブル | フェーズ | 本機能での用途 |
|---------|---------|--------------|
| `team_memberships` | F01.2 | ユーザーが所属するチーム一覧（タグ候補の源・`last_accessed_at` で既定順） |
| 組織ロール（`user_roles` 等） | F01.2 | ユーザーが所属する組織一覧（タグ候補の源） |
| `my_scope_folders` / `my_scope_folder_items` | F15.3 | タグの**任意フィルタ**（フォルダ選択でタグ対象集合を絞り込み・読み取りのみ） |
| `dashboard_widget_settings` | F02.2 | チーム/組織パネルのウィジェット可視性・並び順（既存をそのまま使用） |

> **アーキテクチャ注記**:
> 本機能で新規作成するテーブルは `dashboard_scope_tab_order` の 1 つのみ。タグ候補（所属チーム/組織）そのものは既存の `team_memberships` / 組織ロールから導出するため**新規テーブルを持たない**。
> `dashboard_scope_tab_order` はユーザーごとに行が増えていく設定テーブルであり、シャーディング対象（原則 6 適用）のため UUIDv7 主キーとする。

---

## 2. テーブル定義

### 2.1 dashboard_scope_tab_order

ユーザーが「チーム」または「組織」タグの**表示順**を保存するテーブル。1 ユーザー × スコープ（TEAM / ORGANIZATION）× スコープ ID ごとに最大 1 行。
表示順が保存されていないスコープはサービス層で末尾（`last_accessed_at` 降順）に補完するため、**全所属スコープ分の行を必ず持つ必要はない**（ユーザーが並べ替えたものだけ INSERT）。

```sql
CREATE TABLE dashboard_scope_tab_order (
    id          BINARY(16)   NOT NULL COMMENT 'UUIDv7 主キー（原則6）',
    user_id     BIGINT       NOT NULL COMMENT 'users.id（FK制約なし。クロスドメインFK禁止原則）',
    scope_type  VARCHAR(20)  NOT NULL COMMENT 'タグ種別（TEAM / ORGANIZATION）',
    scope_id    BIGINT       NOT NULL COMMENT 'チームID または 組織ID（FK制約なし）',
    sort_order  INT          NOT NULL DEFAULT 0 COMMENT '表示順（昇順。小さいほど先頭）',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_dsto_user_scope (user_id, scope_type, scope_id),
    INDEX idx_dsto_user_scope_sort (user_id, scope_type, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ダッシュボード横スワイプ：チーム/組織タグの表示順（ユーザー個人設定）';
```

| カラム | 型 | NULL | デフォルト | 説明 |
|-------|----|------|-----------|------|
| `id` | BINARY(16) | NO | — | UUIDv7（`UuidV7Entity` 継承で自動採番） |
| `user_id` | BIGINT | NO | — | `users.id`（FK 制約なし。整合性はアプリ層） |
| `scope_type` | VARCHAR(20) | NO | — | `TEAM` / `ORGANIZATION` のいずれか（サービス層 enum バリデーション） |
| `scope_id` | BIGINT | NO | — | チーム ID または 組織 ID（FK 制約なし） |
| `sort_order` | INT | NO | 0 | 昇順。タグ行の左→右、ページ送りの基準 |
| `created_at` | DATETIME(6) | NO | CURRENT_TIMESTAMP(6) | |
| `updated_at` | DATETIME(6) | NO | CURRENT_TIMESTAMP(6) ON UPDATE | |

**インデックス設計**:

| インデックス | カラム | 用途 |
|------------|-------|------|
| `PRIMARY` | `id` | 主キー |
| `uq_dsto_user_scope` | `(user_id, scope_type, scope_id)` | 1 ユーザー × スコープの一意制約。UPSERT のキー |
| `idx_dsto_user_scope_sort` | `(user_id, scope_type, sort_order)` | タグ一覧取得（表示順適用 + ページング）の主クエリ |

**制約・備考**:

- `dashboard_widget_settings`（F02.2）の `scope_id = 0` sentinel パターンとは異なり、本テーブルは PERSONAL を扱わない（個人パネルにタグはない）。`scope_type` は `TEAM` / `ORGANIZATION` のみ。
- 表示順未保存のスコープ（行なし）は、サービス層で「保存済み行（sort_order 昇順）→ 未保存スコープ（`team_memberships.last_accessed_at` 降順）」の順に並べてからページングする。
- 退会したスコープ・権限を失ったスコープの行が残存しても、タグ一覧取得時に「現在の所属スコープ集合」と突き合わせて自動除外する（バッチクリーンアップ不要。F02.2 の「削除済みウィジェット key 残存」と同じ哲学）。
- 論理削除は**不要**（再設定可能な個人設定であり、行を物理削除しても情報損失なし）。

---

## 3. 設計原則への準拠（CLAUDE.md アーキテクチャ思想 対照表）

| 原則 | 内容 | 本設計での準拠 |
|------|------|--------------|
| **原則 1** | クロスドメイン FK を作らない | `user_id` / `scope_id` に FK 制約を**設けない**。整合性はサービス層（所属チェック）で保証 |
| **原則 2** | CASCADE DELETE は同一ドメイン内のみ | FK 自体を持たないため CASCADE も持たない。チーム/組織削除時の連鎖は発生しない（残存行はタグ取得時にフィルタ除外） |
| **原則 3** | コアエンティティは論理削除 | 本テーブルはコアエンティティ（users/teams/orgs）ではない。論理削除不要 |
| **原則 4** | ユーザー退会時は匿名化（削除しない） | 本テーブルは PII を含まない**個人設定**。退会時は §5 の方針で**削除**する（再設定可能データの即時消去対象。CLAUDE.md §13.12 弱匿名化区分） |
| **原則 5** | `@Transactional` はドメイン内に閉じる | タグ表示順の読み書きは dashboard ドメイン内で完結。タグ候補（所属）取得は team/user ドメインの Service 呼び出し経由。統合要対応集計はファサードで各ドメイン Service を呼ぶ（02 §3.4 参照） |
| **原則 6** | 新規テーブルの主キーは UUIDv7 | `id BINARY(16)`、Entity は `UuidV7Entity` を継承。ユーザーごとに行が増える設定テーブル = シャーディング対象のため適用 |
| **原則 7** | テナントスコープ Repo は `AbstractTenantAwareRepository` | 本テーブルは `organization_id` カラムを**持たない**（user_id 単位の個人設定であり組織テナント単位ではない）ため、`AbstractTenantAwareRepository` の対象外。通常の `JpaRepository<DashboardScopeTabOrderEntity, UUID>` を使用し、全クエリに `user_id` 条件を付与 |

> **原則 7 不適用の判断記録**: 本テーブルはユーザー個人の設定であり、`organization_id` を持たない。`AbstractTenantAwareRepository` は `organization_id` 絞り込みの統一が目的のため、user_id 単位の本テーブルには適用しない。代わりに Repository の全メソッドで `user_id` を必須条件とし、IDOR を防ぐ（02 §2・03 §1.2 参照）。

---

## 4. ER図（テキスト形式）

```
users（既存・コアエンティティ）
  id BIGINT PK
    │ アプリ層のみで整合性保証（FK制約なし）
    ▼
dashboard_scope_tab_order（新規・UUIDv7）
  id BINARY(16) PK
  user_id BIGINT ──────────────┐
  scope_type VARCHAR(20)       │ UNIQUE(user_id, scope_type, scope_id)
  scope_id BIGINT ─────────────┘
  sort_order INT

         scope_id は以下を指す（FK制約なし・アプリ層で所属検証）:
           scope_type=TEAM         → teams.id        （F01.2）
           scope_type=ORGANIZATION → organizations.id（F01.2）

タグ候補の源（既存・読み取りのみ）:
  team_memberships（user_id, team_id, last_accessed_at, left_at）  → TEAM タグ候補
  user_roles 等（user_id, organization_id）                        → ORGANIZATION タグ候補

タグの任意フィルタ（F15.3・読み取りのみ）:
  my_scope_folders（id, user_id, name）
    └─ my_scope_folder_items（folder_id, scope_type, scope_id）  → フォルダ選択で対象集合を絞り込み
```

---

## 5. Flyway マイグレーション方針

**ファイル名（仮）**: `V**.***__create_dashboard_scope_tab_order.sql`

> **【重要】番号は実装時（マージ時）に確定する**。
> Flyway のバージョン番号は「マージ時」に確定し、並行 PR と衝突する（`feedback_migration_version_collision`）。
> 実装着手時に **`origin/main` の最新マイグレーション番号を再確認**し、最大番号 +1 にリネームすること。
> 2026-05-30 時点の `origin/main` 最新は **`V9.180`**（`V9.180__add_manage_reservations_permission.sql`）。実装時にはこれより大きい未使用番号を採番する。
> from-scratch 番人テスト（全マイグレーション再適用）が番号重複を検知するため、マージ直前に再確認すること。

```sql
-- =============================================================
-- F22.1: 個人/チーム/組織 横スワイプ・ダッシュボード
--   ダッシュボードのチーム/組織タグ表示順（ユーザー個人設定）
-- =============================================================

CREATE TABLE dashboard_scope_tab_order (
    id          BINARY(16)  NOT NULL,
    user_id     BIGINT      NOT NULL,
    scope_type  VARCHAR(20) NOT NULL,
    scope_id    BIGINT      NOT NULL,
    sort_order  INT         NOT NULL DEFAULT 0,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_dsto_user_scope (user_id, scope_type, scope_id),
    INDEX idx_dsto_user_scope_sort (user_id, scope_type, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- シードデータなし（ユーザー操作で増えるテーブル）。
- ロールバック方針: `DROP TABLE dashboard_scope_tab_order`（個人設定のみのため安全）。

---

## 6. 設計判断記録

| # | 論点 | 判断 | 理由 |
|---|------|------|------|
| 1 | タグ候補そのものを保存するか | **保存しない**（既存 `team_memberships` / 組織ロールから導出）| 所属の真実の源は既存テーブル。重複保持は同期ズレの温床。本テーブルは「表示順」のみを担う |
| 2 | 主キー | UUIDv7（BINARY(16)）| 原則 6。ユーザーごとに行が増える設定テーブル = シャーディング対象 |
| 3 | `AbstractTenantAwareRepository` 適用 | **不適用** | `organization_id` を持たない user_id 単位の個人設定。原則 7 の意図に該当しない（§3 判断記録） |
| 4 | FK 制約 | なし | 原則 1（クロスドメイン FK 禁止）。`user_id`（user ドメイン）・`scope_id`（team/org ドメイン）への FK は張らない |
| 5 | 表示順未保存スコープの扱い | サービス層で `last_accessed_at` 降順に末尾補完 | 全所属分の行を強制 INSERT すると新規参加チームのたびに書き込みが必要。並べ替えた分だけ保存する設計が軽量 |
| 6 | 退会/権限喪失スコープの残存行 | タグ取得時に現在の所属集合と突合し自動除外 | F02.2 の「削除済み key 残存」と同じ。バッチクリーンアップ不要で副作用なし |
| 7 | フォルダ（F15.3）の扱い | 読み取り流用（任意フィルタ）。テーブル新設なし | F15.3 が所有。本機能はフィルタとして `my_scope_folder_items` を参照するのみ |
| 8 | 論理削除 | なし（物理削除）| 再設定可能な個人設定。情報損失リスクなし。退会時は即時物理削除（§5 / CLAUDE.md §13.12 弱匿名化区分） |
