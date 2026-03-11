# F03: 組織・チーム・メンバー・ロール管理

> **ステータス**: 🟢 設計完了
> **実装フェーズ**: Phase 2
> **最終更新**: 2026-03-10

---

## 1. 概要

組織（organizations）・チーム（teams）の作成と管理、メンバーシップの制御、ロール/パーミッション（RBAC）の定義と割り当て、招待URL/QRコードによるメンバー加入を担う中核機能。
個人・チーム・組織の3層構造と「チームAでは DEPUTY_ADMIN、チームBでは MEMBER」のようなマルチスコープ所属を実現する。
DEPUTY_ADMIN の細粒度な権限制御は、ADMIN が名前付き「権限グループ」を作成してユーザーへ割り当てる方式で実現する。

---

## 2. スコープ

### 対象ロール
| ロール | 操作可能な範囲 |
|--------|--------------|
| SYSTEM_ADMIN | 全組織・チームの参照・強制削除・ロール変更 |
| ADMIN | 担当チーム/組織の全設定・メンバー管理・招待発行・権限グループ管理 |
| DEPUTY_ADMIN | ADMIN が付与した権限グループの範囲内のみ（招待は INVITE_MEMBERS 権限が必要）|
| MEMBER | デフォルトで MANAGE_SCHEDULES / MANAGE_FILES / MANAGE_POSTS を保持。ADMIN が権限グループを割り当てることで実効権限を上書き設定可能（グループ割り当て時はデフォルト権限を含め全権限がグループ定義で置き換わる）|
| SUPPORTER | 公開チームページから招待コード不要でフォロー（サポーター登録）。チームが `supporter_enabled = TRUE` の場合のみ利用可。ブロック済みユーザーは登録不可 |
| GUEST | 閲覧のみ（招待URL経由で付与）|

### 対象レベル
- [x] 組織 (Organization)
- [x] チーム (Team)
- [x] 個人 (Personal) — メンバーとして参加

---

## 3. DB設計

### テーブル一覧
| テーブル名 | 役割 | 論理削除 |
|-----------|------|---------|
| `organizations` | 組織マスター | あり |
| `teams` | チームマスター | あり |
| `roles` | ロールマスター（固定6種・シード）| なし |
| `permissions` | パーミッションマスター（シード）| なし |
| `role_permissions` | ロール↔パーミッション紐付け（シード）| なし |
| `user_roles` | ユーザーのロール割り当て（スコープ付き）| なし |
| `permission_groups` | DEPUTY_ADMIN / MEMBER 用権限グループ（チーム・組織共通; ADMIN が定義）| あり |
| `permission_group_permissions` | 権限グループ↔パーミッション紐付け | なし |
| `user_permission_groups` | ユーザー↔権限グループ割り当て | なし |
| `invite_tokens` | 招待URL/QRコード用トークン | なし（revoked_at で失効管理）|
| `team_org_memberships` | チーム↔組織の多対多所属関係（組織からの招待・チームの承認で成立）| なし（物理削除。履歴は audit_logs で管理）|
| `team_blocks` | チームのサポーター自己登録ブロックリスト（ADMIN/DEPUTY_ADMIN が管理）| なし |
| `organization_blocks` | 組織のサポーター自己登録ブロックリスト（ADMIN/DEPUTY_ADMIN が管理）| なし |

### テーブル定義

#### `organizations`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `name` | VARCHAR(100) | NO | — | 組織正式名称 |
| `name_kana` | VARCHAR(100) | YES | NULL | フリガナ（地域検索用）|
| `nickname1` | VARCHAR(50) | YES | NULL | 愛称1 |
| `nickname2` | VARCHAR(50) | YES | NULL | 愛称2 |
| `org_type` | ENUM('NONPROFIT', 'FORPROFIT') | NO | 'FORPROFIT' | 組織種別（自己申告制・課金ロジックに影響）|
| `parent_organization_id` | BIGINT UNSIGNED | YES | NULL | 親組織（FK → organizations; NULL = トップレベル）|
| `prefecture` | VARCHAR(10) | YES | NULL | 都道府県 |
| `city` | VARCHAR(50) | YES | NULL | 市区町村 |
| `description` | TEXT | YES | NULL | 組織説明（最大2000文字はアプリ層でバリデーション）|
| `icon_url` | VARCHAR(512) | YES | NULL | アイコン画像URL（S3）|
| `banner_url` | VARCHAR(512) | YES | NULL | バナー画像URL（S3）|
| `visibility` | ENUM('PUBLIC', 'PRIVATE') | NO | 'PRIVATE' | 情報公開レベル（外部公開制御）|
| `hierarchy_visibility` | ENUM('NONE', 'BASIC', 'FULL') | NO | 'NONE' | 子組織・チームのメンバーに対するこの組織の閲覧範囲。NONE=非公開 / BASIC=組織名・説明・アイコンのみ / FULL=visibility 設定範囲内の全コンテンツ |
| `supporter_enabled` | BOOLEAN | NO | FALSE | サポーター（フォロー）登録機能の有効化フラグ。TRUE かつ visibility=PUBLIC の場合のみ招待コード不要でフォロー可能 |
| `archived_at` | DATETIME | YES | NULL | アーカイブ日時（NULL = アクティブ）|
| `deleted_at` | DATETIME | YES | NULL | 論理削除日時 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_org_parent (parent_organization_id)
INDEX idx_org_archived (archived_at)    -- アーカイブバッチ用
INDEX idx_org_name (name)               -- 検索用
```

**制約・備考**
- 論理削除: `deleted_at DATETIME nullable`
- `parent_organization_id` の循環参照はアプリケーション層で防ぐ。最大深さは `app.org.max-depth`（デフォルト: 5）で管理し、Service 層がこの設定値を参照して depth を検証する
- `hierarchy_visibility` はこの組織を「子から上向きに見たとき」の可視範囲を制御する。`visibility`（外部からの検索・閲覧）とは独立して設定できる

---

#### `teams`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `name` | VARCHAR(100) | NO | — | チーム/店舗/教室 正式名称 |
| `name_kana` | VARCHAR(100) | YES | NULL | フリガナ |
| `nickname1` | VARCHAR(50) | YES | NULL | 愛称1 |
| `nickname2` | VARCHAR(50) | YES | NULL | 愛称2 |
| `template` | VARCHAR(50) | YES | NULL | テンプレート種別（例: SPORTS, CLINIC, SCHOOL）。Phase 2 は VARCHAR(50) のままアプリ層の enum 定数でバリデーション。メタデータ（カスタムフィールド等）が必要になった段階で `team_templates` テーブルを新設し FK へ移行 |
| `prefecture` | VARCHAR(10) | YES | NULL | 都道府県 |
| `city` | VARCHAR(50) | YES | NULL | 市区町村 |
| `description` | TEXT | YES | NULL | チーム説明 |
| `icon_url` | VARCHAR(512) | YES | NULL | アイコン画像URL（S3）|
| `banner_url` | VARCHAR(512) | YES | NULL | バナー画像URL（S3）|
| `visibility` | ENUM('PUBLIC', 'ORGANIZATION_ONLY', 'PRIVATE') | NO | 'PRIVATE' | 情報公開レベル |
| `supporter_enabled` | BOOLEAN | NO | FALSE | サポーター（フォロー）登録機能の有効化フラグ。TRUE かつ visibility=PUBLIC の場合のみ招待コード不要でフォロー可能 |
| `archived_at` | DATETIME | YES | NULL | アーカイブ日時 |
| `deleted_at` | DATETIME | YES | NULL | 論理削除日時 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_team_archived (archived_at)
INDEX idx_team_pref_city (prefecture, city)   -- 地域検索用
INDEX idx_team_name (name)
```

**制約・備考**
- 論理削除: `deleted_at DATETIME nullable`
- 組織との多対多所属関係は `team_org_memberships` テーブルで管理する。チームは複数の組織に同時所属可能
- `visibility = 'ORGANIZATION_ONLY'` は `team_org_memberships` に ACTIVE なエントリが1件以上存在する場合のみ有効（アプリ層でバリデーション。所属組織がない状態での設定は 422）
- アーカイブトリガー: 全メンバーの最終ログインのうち最新が12ヶ月経過（README §アーカイブ規約参照）
- `template`: Phase 2 は VARCHAR(50) で運用（アプリ層 enum 定数でバリデーション）。`team_templates` への FK 移行は「テンプレートごとのメタデータが必要になった段階」でテンプレート管理 feature doc にて設計・実施する

---

#### `roles`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `name` | VARCHAR(50) | NO | — | ロール識別子（例: SYSTEM_ADMIN）|
| `display_name` | VARCHAR(100) | NO | — | 表示名（日本語）|
| `description` | VARCHAR(500) | YES | NULL | 説明 |
| `priority` | TINYINT UNSIGNED | NO | — | 優先度（小さいほど上位; 表示順・昇格制限に使用）|
| `is_system` | BOOLEAN | NO | TRUE | システム固定ロール（アプリ層で削除不可）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_role_name (name)
```

**シードデータ**
| id | name | display_name | priority |
|----|------|-------------|----------|
| 1 | SYSTEM_ADMIN | システム管理者 | 1 |
| 2 | ADMIN | 管理者 | 2 |
| 3 | DEPUTY_ADMIN | 副管理者 | 3 |
| 4 | MEMBER | メンバー | 4 |
| 5 | SUPPORTER | サポーター | 5 |
| 6 | GUEST | ゲスト | 6 |

**制約・備考**
- Phase 1 は固定ロールのみ。将来のカスタムロールは `is_system = FALSE` の行として追加
- ADMIN は `priority >= 3`（DEPUTY_ADMIN 以下）のロールのみ付与可能

---

#### `permissions`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `name` | VARCHAR(100) | NO | — | パーミッション識別子（例: MANAGE_MEMBERS）|
| `display_name` | VARCHAR(100) | NO | — | 表示名（日本語）|
| `description` | VARCHAR(500) | YES | NULL | 説明 |
| `scope` | ENUM('PLATFORM', 'ORGANIZATION', 'TEAM') | NO | 'TEAM' | 適用スコープ |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_permission_name (name)
```

**Phase 2 初期シードデータ（抜粋）**

| name | display_name | scope |
|------|-------------|-------|
| `INVITE_MEMBERS` | メンバー招待 | TEAM |
| `REMOVE_MEMBERS` | メンバー除名 | TEAM |
| `CHANGE_MEMBER_ROLES` | メンバーロール変更（DEPUTY_ADMIN 以下）| TEAM |
| `MANAGE_INVITE_TOKENS` | 招待URL管理 | TEAM |
| `EDIT_TEAM_SETTINGS` | チーム設定編集 | TEAM |
| `MANAGE_SCHEDULES` | スケジュール作成・編集・削除 | TEAM |
| `MANAGE_FILES` | ファイルアップロード・削除 | TEAM |
| `MANAGE_POSTS` | 投稿作成・編集 | TEAM |
| `DELETE_OTHERS_CONTENT` | 他メンバーのコンテンツ削除（デフォルト非付与）| TEAM |
| `MANAGE_ANNOUNCEMENTS` | お知らせ配信 | TEAM |
| `SEND_SAFETY_CONFIRMATION` | 安否確認送信 | TEAM |
| `MANAGE_PAYMENTS` | 支払い管理（F04; Phase 3 追加予定）| ORGANIZATION |

> 各機能モジュール（スケジュール・ファイル・チャット等）の実装時に順次追加する。`MANAGE_PAYMENTS` の scope は F04 の確定設計（チーム・組織の両方で支払い管理 API が存在）に基づき `ORGANIZATION` に確定した。チームスコープの DEPUTY_ADMIN に対しても、permission_group を通じて同一パーミッション名で委譲できる（payment_items は team_id / organization_id いずれかで紐付けられるため、スコープ判定はアプリ層で実施）。

---

#### `role_permissions`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `role_id` | BIGINT UNSIGNED | NO | — | FK → roles |
| `permission_id` | BIGINT UNSIGNED | NO | — | FK → permissions |
| `is_default` | BOOLEAN | NO | TRUE | TRUE = ロール保持者全員に自動付与 / FALSE = 天井定義のみ（権限グループ経由で個別付与）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_rp_role_permission (role_id, permission_id)
INDEX idx_rp_permission (permission_id)
```

**シードデータ（V2.015__seed_role_permissions.sql / V2.021__seed_member_permission_ceiling.sql / Phase 3: V3.007__add_manage_payments_permission.sql）**

凡例: **✓** = is_default TRUE（自動付与） / **△** = is_default FALSE（天井のみ・権限グループ経由で個別付与可）

| パーミッション | SYSTEM_ADMIN | ADMIN | DEPUTY_ADMIN | MEMBER | SUPPORTER | GUEST |
|--------------|:---:|:---:|:---:|:---:|:---:|:---:|
| `INVITE_MEMBERS` | ✓ | ✓ | △ | - | - | - |
| `REMOVE_MEMBERS` | ✓ | ✓ | △ | - | - | - |
| `CHANGE_MEMBER_ROLES` | ✓ | ✓ | △ | - | - | - |
| `MANAGE_INVITE_TOKENS` | ✓ | ✓ | △ | - | - | - |
| `EDIT_TEAM_SETTINGS` | ✓ | ✓ | △ | - | - | - |
| `MANAGE_SCHEDULES` | ✓ | ✓ | △ | ✓ | - | - |
| `MANAGE_FILES` | ✓ | ✓ | △ | ✓ | - | - |
| `MANAGE_POSTS` | ✓ | ✓ | △ | ✓ | - | - |
| `DELETE_OTHERS_CONTENT` | ✓ | ✓ | △ | △ | - | - |
| `MANAGE_ANNOUNCEMENTS` | ✓ | ✓ | △ | △ | - | - |
| `SEND_SAFETY_CONFIRMATION` | ✓ | ✓ | △ | △ | - | - |
| `MANAGE_PAYMENTS` | ✓ | ✓ | △ | - | - | - |

> ※ `MANAGE_PAYMENTS` は Phase 3 / V3.007 で追加

Phase 2 合計レコード数: 11 + 11 + 11 + 6（✓3 + △3） = **39件**
Phase 3 追加（MANAGE_PAYMENTS）: SYSTEM_ADMIN ✓ + ADMIN ✓ + DEPUTY_ADMIN △ = **+3件 → 合計42件**

**制約・備考**
- **SYSTEM_ADMIN**: Phase 3 以降 全12件（is_default = TRUE）。権限チェックは JWT 判定に統一（runtime で DB 参照しない）。シードは監査・将来対応のため投入する
- **ADMIN**: Phase 3 以降 全12件（is_default = TRUE）。`DELETE_OTHERS_CONTENT` / `MANAGE_PAYMENTS` を含む全パーミッションを行使可能
- **DEPUTY_ADMIN**: Phase 3 以降 全12件（is_default = FALSE）。**天井（ceiling）定義**として機能する。runtime での権限解決は role_permissions を参照せず `user_permission_groups` のみを使用する（権限グループ未割り当ての DEPUTY_ADMIN は実効パーミッション 0）
- **MEMBER（is_default = TRUE）**: `MANAGE_SCHEDULES` / `MANAGE_FILES` / `MANAGE_POSTS` の3件。チーム参加と同時に全 MEMBER へ自動付与
- **MEMBER（is_default = FALSE）**: `DELETE_OTHERS_CONTENT` / `MANAGE_ANNOUNCEMENTS` / `SEND_SAFETY_CONFIRMATION` の3件。天井のみ（自動付与なし）。ADMIN が MEMBER 用権限グループを作成し特定ユーザーへ割り当てた場合のみ有効
- **`MANAGE_PAYMENTS`**: MEMBER の role_permissions に含めない（天井エントリなし）。MEMBER は支払い管理権限を付与不可
- **`DELETE_OTHERS_CONTENT`**: DEPUTY_ADMIN / MEMBER いずれの天井にも含める。ただしいかなるデフォルト権限グループにも含めない。ADMIN が意図的に付与した場合のみ有効
- **SUPPORTER / GUEST**: role_permissions なし。閲覧権限はロールチェックで制御し、パーミッションテーブルは参照しない

---

#### `user_roles`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users |
| `role_id` | BIGINT UNSIGNED | NO | — | FK → roles |
| `team_id` | BIGINT UNSIGNED | YES | NULL | FK → teams（チームスコープ; NULL = 組織またはプラットフォーム）|
| `organization_id` | BIGINT UNSIGNED | YES | NULL | FK → organizations（組織スコープ; NULL = チームまたはプラットフォーム）|
| `scope_key` | VARCHAR(30) | NO | — | STORED 生成列。`COALESCE(CONCAT('org:', organization_id), CONCAT('team:', team_id), 'platform')` で導出。一意性保証用 |
| `granted_by` | BIGINT UNSIGNED | YES | NULL | FK → users（付与者; システム付与・シードは NULL; SET NULL on delete）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_ur_user_scope (user_id, scope_key)   -- DBレベルの一意性保証（同一スコープへの重複ロール防止）
INDEX idx_ur_user_id (user_id)
INDEX idx_ur_team_id (team_id)
INDEX idx_ur_organization_id (organization_id)
INDEX idx_ur_user_team (user_id, team_id)
INDEX idx_ur_user_org (user_id, organization_id)
```

**スコープ規則**
| スコープ | team_id | organization_id | 例 |
|---------|---------|----------------|-----|
| プラットフォーム | NULL | NULL | SYSTEM_ADMIN |
| 組織レベル | NULL | X | 組織 X の ADMIN |
| チームレベル | Y | NULL | チーム Y の DEPUTY_ADMIN |

**制約・備考**
- 1ユーザーが同一スコープに複数ロールを持つことはできない。`UNIQUE KEY uq_ur_user_scope (user_id, scope_key)` で DB レベルで保証する
- `scope_key` は STORED 生成列（`COALESCE(CONCAT('org:', organization_id), CONCAT('team:', team_id), 'platform')`）。MySQL の UNIQUE 制約が NULL を重複と見なさない仕様を回避し、`SELECT FOR UPDATE` によるロック競合なしに一意性を強制できる
- 重複挿入時は DB が一意制約違反を発生させるため、Service 層は例外ハンドリングのみで対応可（挿入前の重複確認クエリは不要）
- `team_id` と `organization_id` を同時に非 NULL にすることはアプリ層で禁止（両方 NULL はプラットフォームスコープとして有効。`permission_groups` / `invite_tokens` と異なり「両方 NULL 可」のため XOR 制約ではなく `NOT (team_id IS NOT NULL AND organization_id IS NOT NULL)` を CHECK 制約として追加する）
- チーム論理削除後も `user_roles` は保持する。削除済みチームはアプリ層でフィルタリング

---

#### `permission_groups`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | YES | NULL | FK → teams（チームスコープ; NULL = 組織スコープ）|
| `organization_id` | BIGINT UNSIGNED | YES | NULL | FK → organizations（組織スコープ; NULL = チームスコープ）|
| `target_role` | ENUM('DEPUTY_ADMIN', 'MEMBER') | NO | 'DEPUTY_ADMIN' | このグループの対象ロール。DEPUTY_ADMIN = 天井内のパーミッションを個別付与 / MEMBER = MEMBER 天井内のパーミッションを特定ユーザーに追加付与 |
| `name` | VARCHAR(100) | NO | — | グループ名（例: お知らせ編集担当）|
| `description` | VARCHAR(500) | YES | NULL | 説明 |
| `created_by` | BIGINT UNSIGNED | YES | NULL | FK → users（作成 ADMIN; SET NULL on delete）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |
| `deleted_at` | DATETIME | YES | NULL | 論理削除 |

**インデックス**
```sql
INDEX idx_pg_team_id (team_id)
INDEX idx_pg_team_role (team_id, target_role)           -- チーム: ロール別グループ一覧の取得用
INDEX idx_pg_org_id (organization_id)
INDEX idx_pg_org_role (organization_id, target_role)    -- 組織: ロール別グループ一覧の取得用
```

**制約・備考**
- `team_id` と `organization_id` はどちらか一方のみ非 NULL（XOR; アプリ層でバリデーション）
  - MySQL CHECK 制約: `CONSTRAINT chk_pg_scope CHECK ((team_id IS NULL) != (organization_id IS NULL))`
- 論理削除: `deleted_at DATETIME nullable`
- 論理削除時は紐付く `user_permission_groups` も同時削除（アプリ層で処理）
- `target_role = 'DEPUTY_ADMIN'` のグループに追加できるパーミッションは `role_permissions WHERE role_id = DEPUTY_ADMIN AND is_default = FALSE` の範囲内（アプリ層でバリデーション）
- `target_role = 'MEMBER'` のグループに追加できるパーミッションは `role_permissions WHERE role_id = MEMBER`（is_default 問わず全6件）の範囲内。グループが割り当てられると is_default の3件も自動付与されなくなるため、基本権限を維持したい場合はグループ内に明示的に含める必要がある

---

#### `permission_group_permissions`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `group_id` | BIGINT UNSIGNED | NO | — | FK → permission_groups |
| `permission_id` | BIGINT UNSIGNED | NO | — | FK → permissions |

**インデックス**
```sql
UNIQUE KEY uq_pgp_group_permission (group_id, permission_id)
INDEX idx_pgp_permission (permission_id)
```

---

#### `user_permission_groups`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（DEPUTY_ADMIN または MEMBER 対象ユーザー）|
| `group_id` | BIGINT UNSIGNED | NO | — | FK → permission_groups |
| `assigned_by` | BIGINT UNSIGNED | YES | NULL | FK → users（割り当てた ADMIN; SET NULL on delete）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_upg_user_group (user_id, group_id)
INDEX idx_upg_group_id (group_id)
```

**制約・備考**
- 1ユーザーに複数の権限グループを割り当て可能（M:N）
- 割り当て対象ユーザーは当該スコープ（チームまたは組織）の DEPUTY_ADMIN または MEMBER（アプリ層でバリデーション）
- 割り当て時に `permission_groups.target_role` と対象ユーザーのロールが一致することを確認する（MEMBER に DEPUTY_ADMIN 用グループを割り当てることは不可）
- 異なるスコープのグループを割り当てることは不可（チームの DEPUTY_ADMIN に組織のグループを割り当てる等; アプリ層でバリデーション）

---

#### `invite_tokens`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `token` | CHAR(36) | NO | — | UUID v4 トークン（公開URL/QRコードに埋め込む）|
| `team_id` | BIGINT UNSIGNED | YES | NULL | FK → teams（チーム招待; NULL = 組織招待）|
| `organization_id` | BIGINT UNSIGNED | YES | NULL | FK → organizations（組織招待; NULL = チーム招待）|
| `role_id` | BIGINT UNSIGNED | NO | — | FK → roles（参加時に付与するロール）|
| `created_by` | BIGINT UNSIGNED | YES | NULL | FK → users（発行者; SET NULL on delete）|
| `expires_at` | DATETIME | YES | NULL | 有効期限（NULL = 無期限）|
| `max_uses` | INT UNSIGNED | YES | NULL | 使用回数上限（NULL = 無制限）|
| `used_count` | INT UNSIGNED | NO | 0 | 現在の使用回数 |
| `revoked_at` | DATETIME | YES | NULL | 手動無効化日時（NULL = 有効）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_it_token (token)
INDEX idx_it_team_id (team_id)
INDEX idx_it_organization_id (organization_id)
```

**制約・備考**
- `team_id` と `organization_id` はどちらか一方のみ非 NULL（真の XOR; 両方 NULL は不正なため DB レベルの CHECK 制約 `chk_it_scope` で保証する。`permission_groups` の `chk_pg_scope` と同方式）
- `used_count` インクリメントは `SELECT ... FOR UPDATE` でアトミックに実行（同時参加による上限超過防止）
- 有効期限の選択肢: 1日 / 7日 / 30日 / 90日 / 無期限
- **発行者退会時の扱い**: `created_by` は `SET NULL on delete`。発行者が退会してもトークンは自動失効させず有効のままとする。理由: ① 引退・卒業等により発行者が交代しても既存の募集 URL が無効にならないよう運用継続性を保つため ② 招待 URL の管理責任は個人ではなくチーム/組織に帰属するため ③ 必要な場合は他の ADMIN が `revoked_at` を手動設定して失効させることが可能なため
- **チーム/組織論理削除時の扱い**: 対象エンティティが論理削除された際、紐付くすべてのトークン（`revoked_at IS NULL` のもの）に `revoked_at = NOW()` を一括設定する。存在しないエンティティへの参加導線を残さないため。実装: チーム/組織削除 Service メソッド内でトランザクション内に一括 UPDATE を含める（`WHERE team_id/organization_id = :id AND revoked_at IS NULL`）

---

#### `team_blocks`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（ブロック対象; ON DELETE CASCADE）|
| `blocked_by` | BIGINT UNSIGNED | YES | NULL | FK → users（操作した ADMIN/DEPUTY_ADMIN; SET NULL on delete）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_tb_team_user (team_id, user_id)
INDEX idx_tb_team_id (team_id)
```

**制約・備考**
- ブロック済みユーザーは `POST /teams/{id}/follow` による自己登録が不可
- ブロックは SUPPORTER の自己登録（フォロー）を防ぐ目的。ADMIN が手動でロールを付与することは妨げない
- `user_id ON DELETE CASCADE`: ユーザー退会後はブロック記録も不要なため削除
- ADMIN 以上のロールを持つユーザーをブロックすることはアプリ層で禁止（上位ロールは対象外）

---

#### `organization_blocks`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `organization_id` | BIGINT UNSIGNED | NO | — | FK → organizations |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（ブロック対象; ON DELETE CASCADE）|
| `blocked_by` | BIGINT UNSIGNED | YES | NULL | FK → users（操作した ADMIN/DEPUTY_ADMIN; SET NULL on delete）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_ob_org_user (organization_id, user_id)
INDEX idx_ob_organization_id (organization_id)
```

**制約・備考**
- `team_blocks` と同一の設計ポリシーを適用（ブロック対象は自己登録のみ制限・ADMIN手動付与は妨げない）
- `user_id ON DELETE CASCADE`: ユーザー退会後はブロック記録も削除

---

#### `team_org_memberships`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams（ON DELETE CASCADE）|
| `organization_id` | BIGINT UNSIGNED | NO | — | FK → organizations（ON DELETE CASCADE）|
| `status` | ENUM('PENDING', 'ACTIVE') | NO | 'PENDING' | PENDING = 承認待ち / ACTIVE = 所属中 |
| `invited_by` | BIGINT UNSIGNED | YES | NULL | FK → users（招待した組織 ADMIN; SET NULL on delete）|
| `responded_by` | BIGINT UNSIGNED | YES | NULL | FK → users（承認/拒否したチーム ADMIN; SET NULL on delete）|
| `invited_at` | DATETIME | NO | CURRENT_TIMESTAMP | 招待日時 |
| `responded_at` | DATETIME | YES | NULL | 承認または拒否した日時 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_tom_team_org (team_id, organization_id)
INDEX idx_tom_team_id (team_id)
INDEX idx_tom_org_id (organization_id)
INDEX idx_tom_status (status)
```

**制約・備考**
- 物理削除で管理。承認拒否・招待取消・チーム離脱・組織除名はいずれも DELETE で終了し、履歴は audit_logs で管理
- 再招待（拒否・取消後）は新規 INSERT で再開始する（UNIQUE KEY により同一ペアの PENDING/ACTIVE は常に最大1件に限定）
- チームは複数の組織に同時所属可能（UNIQUE は (team_id, organization_id) ペアに対してのみ）
- 組織の物理削除時: ON DELETE CASCADE により紐付く全レコードが自動削除。論理削除時は ON DELETE CASCADE が発動しないため、アプリ層で明示的に DELETE する（組織論理削除フロー参照）

---

### ER図（テキスト形式）
```
organizations (1) ──── (N) organizations        ※ parent_organization_id（自己参照）
teams (N) ──── (M) organizations                ※ via team_org_memberships（複数組織所属可）
users (N) ──── (M) teams / organizations        ※ via user_roles（スコープ付き）
roles (1) ──── (N) user_roles
roles (1) ──── (N) role_permissions
permissions (1) ──── (N) role_permissions
teams / organizations (1) ──── (N) permission_groups    ※ team_id または organization_id（XOR）
permission_groups (1) ──── (N) permission_group_permissions
permissions (1) ──── (N) permission_group_permissions
users (N) ──── (M) permission_groups            ※ via user_permission_groups
teams / organizations (1) ──── (N) invite_tokens
teams (1) ──── (N) team_blocks              ※ supporter_enabled チームのブロックリスト
organizations (1) ──── (N) organization_blocks  ※ supporter_enabled 組織のブロックリスト
teams (1) ──── (N) team_org_memberships
organizations (1) ──── (N) team_org_memberships
```

---

## 4. API設計

### エンドポイント一覧
| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| POST | `/api/v1/organizations` | 必要 | 組織作成 |
| GET | `/api/v1/organizations/{id}` | 任意 | 組織詳細取得（可視性による）|
| PATCH | `/api/v1/organizations/{id}` | 必要（ADMIN+）| 組織情報更新 |
| DELETE | `/api/v1/organizations/{id}` | 必要（ADMIN+）| 組織論理削除 |
| GET | `/api/v1/organizations/{id}/members` | 必要（visibility = PUBLIC は外部閲覧可）| 組織メンバー一覧（直接所属・visibility 依存の認可・返却粒度あり）|
| PATCH | `/api/v1/organizations/{id}/members/{userId}/role` | 必要（ADMIN）| 組織メンバーロール変更 |
| DELETE | `/api/v1/organizations/{id}/members/{userId}` | 必要（ADMIN）| 組織メンバー除名 |
| POST | `/api/v1/organizations/{id}/invite-tokens` | 必要（ADMIN / DEPUTY_ADMIN※）| 組織招待トークン発行（※INVITE_MEMBERS + MANAGE_INVITE_TOKENS 権限必要）|
| GET | `/api/v1/organizations/{id}/invite-tokens` | 必要（ADMIN / DEPUTY_ADMIN※）| 組織招待トークン一覧（※MANAGE_INVITE_TOKENS 権限必要）|
| DELETE | `/api/v1/organizations/{id}/invite-tokens/{tokenId}` | 必要（ADMIN / DEPUTY_ADMIN※）| 組織招待トークン失効（※MANAGE_INVITE_TOKENS 権限必要）|
| POST | `/api/v1/teams` | 必要 | チーム作成 |
| GET | `/api/v1/teams/{id}` | 任意 | チーム詳細取得（可視性による）|
| PATCH | `/api/v1/teams/{id}` | 必要（ADMIN+）| チーム情報更新 |
| DELETE | `/api/v1/teams/{id}` | 必要（ADMIN+）| チーム論理削除 |
| GET | `/api/v1/teams/{id}/members` | 必要（visibility = PUBLIC / ORGANIZATION_ONLY は外部閲覧可）| チームメンバー一覧（visibility 依存の認可・返却粒度あり）|
| PATCH | `/api/v1/teams/{id}/members/{userId}/role` | 必要（ADMIN）| メンバーロール変更 |
| DELETE | `/api/v1/teams/{id}/members/{userId}` | 必要（ADMIN）| メンバー除名 |
| POST | `/api/v1/teams/{id}/invite-tokens` | 必要（ADMIN / DEPUTY_ADMIN※）| チーム招待トークン発行（※INVITE_MEMBERS + MANAGE_INVITE_TOKENS 権限必要）|
| GET | `/api/v1/teams/{id}/invite-tokens` | 必要（ADMIN / DEPUTY_ADMIN※）| チーム招待トークン一覧（※MANAGE_INVITE_TOKENS 権限必要）|
| DELETE | `/api/v1/teams/{id}/invite-tokens/{tokenId}` | 必要（ADMIN / DEPUTY_ADMIN※）| 招待トークン失効（※MANAGE_INVITE_TOKENS 権限必要）|
| GET | `/api/v1/invite/{token}` | 不要 | 招待プレビュー（参加前確認）|
| POST | `/api/v1/invite/{token}/join` | 必要 | 招待URLで参加 |
| GET | `/api/v1/teams/{id}/permission-groups` | 必要（ADMIN）| 権限グループ一覧（`?target_role=DEPUTY_ADMIN\|MEMBER` でフィルタ可）|
| POST | `/api/v1/teams/{id}/permission-groups` | 必要（ADMIN）| 権限グループ作成（`target_role` で DEPUTY_ADMIN / MEMBER を指定）|
| PATCH | `/api/v1/teams/{id}/permission-groups/{groupId}` | 必要（ADMIN）| 権限グループ更新 |
| DELETE | `/api/v1/teams/{id}/permission-groups/{groupId}` | 必要（ADMIN）| 権限グループ論理削除 |
| PUT | `/api/v1/teams/{id}/members/{userId}/permission-groups` | 必要（ADMIN）| DEPUTY_ADMIN / MEMBER への権限グループ一括設定（ユーザーのロールに対応する `target_role` のグループのみ割り当て可）|
| GET | `/api/v1/organizations/{id}/permission-groups` | 必要（ADMIN）| 組織権限グループ一覧（`?target_role=DEPUTY_ADMIN\|MEMBER` でフィルタ可）|
| POST | `/api/v1/organizations/{id}/permission-groups` | 必要（ADMIN）| 組織権限グループ作成（`target_role` で DEPUTY_ADMIN / MEMBER を指定）|
| PATCH | `/api/v1/organizations/{id}/permission-groups/{groupId}` | 必要（ADMIN）| 組織権限グループ更新 |
| DELETE | `/api/v1/organizations/{id}/permission-groups/{groupId}` | 必要（ADMIN）| 組織権限グループ論理削除 |
| PUT | `/api/v1/organizations/{id}/members/{userId}/permission-groups` | 必要（ADMIN）| 組織 DEPUTY_ADMIN / MEMBER への権限グループ一括設定 |
| GET | `/api/v1/permissions` | 必要（ADMIN+）| パーミッションカタログ一覧 |
| GET | `/api/v1/me/teams` | 必要 | 自分が所属するチーム一覧（ロール・参加日時付き）|
| GET | `/api/v1/me/organizations` | 必要 | 自分が所属する組織一覧（ロール・参加日時付き）|
| POST | `/api/v1/teams/{id}/follow` | 必要 | チームをフォロー（SUPPORTER 自己登録・招待コード不要）|
| DELETE | `/api/v1/teams/{id}/follow` | 必要 | フォロー解除（自分の SUPPORTER ロールを削除）|
| GET | `/api/v1/teams/{id}/blocks` | 必要（ADMIN）| ブロック一覧 |
| POST | `/api/v1/teams/{id}/blocks` | 必要（ADMIN/DEPUTY_ADMIN）| ユーザーをブロック（自己登録禁止・現在のロールも同時除名）|
| DELETE | `/api/v1/teams/{id}/blocks/{userId}` | 必要（ADMIN/DEPUTY_ADMIN）| ブロック解除 |
| POST | `/api/v1/organizations/{id}/follow` | 必要 | 組織をフォロー（SUPPORTER 自己登録・招待コード不要）|
| DELETE | `/api/v1/organizations/{id}/follow` | 必要 | 組織フォロー解除 |
| GET | `/api/v1/organizations/{id}/blocks` | 必要（ADMIN）| 組織ブロック一覧 |
| POST | `/api/v1/organizations/{id}/blocks` | 必要（ADMIN/DEPUTY_ADMIN）| 組織ユーザーをブロック |
| DELETE | `/api/v1/organizations/{id}/blocks/{userId}` | 必要（ADMIN/DEPUTY_ADMIN）| 組織ブロック解除 |
| GET | `/api/v1/organizations/{id}/members/all` | 必要（ADMIN+）| 組織サブツリーの全メンバー一覧（WITH RECURSIVE で全子組織・子チームを網羅・カスケード通知対象確認用）|
| DELETE | `/api/v1/teams/{id}/me` | 必要 | 自主退会（ADMIN / DEPUTY_ADMIN / MEMBER が自ら離脱。SUPPORTER は `DELETE /teams/{id}/follow` を使用）|
| DELETE | `/api/v1/organizations/{id}/me` | 必要 | 自主退会（ADMIN / DEPUTY_ADMIN / MEMBER が自ら離脱。SUPPORTER は `DELETE /organizations/{id}/follow` を使用）|
| PATCH | `/api/v1/teams/{id}/archive` | 必要（ADMIN）| チームを手動アーカイブ（`archived_at = NOW()`。招待トークン失効・以降の書き込み操作ブロック）|
| PATCH | `/api/v1/teams/{id}/unarchive` | 必要（ADMIN）| チームアーカイブ解除（`archived_at = NULL`。書き込み操作を再開）|
| PATCH | `/api/v1/organizations/{id}/archive` | 必要（ADMIN）| 組織を手動アーカイブ（`archived_at = NOW()`。招待トークン失効・書き込み操作ブロック）|
| PATCH | `/api/v1/organizations/{id}/unarchive` | 必要（ADMIN）| 組織アーカイブ解除（`archived_at = NULL`）|
| POST | `/api/v1/organizations/{id}/team-invites` | 必要（ADMIN）| 組織からチームへ所属招待を送信 |
| GET | `/api/v1/organizations/{id}/team-invites` | 必要（ADMIN）| 送信済み招待一覧（PENDING のみ）|
| DELETE | `/api/v1/organizations/{id}/team-invites/{teamId}` | 必要（ADMIN）| 招待取消（PENDING を削除）|
| DELETE | `/api/v1/organizations/{id}/teams/{teamId}` | 必要（ADMIN）| 所属チームを除名（ACTIVE を削除）|
| GET | `/api/v1/organizations/{id}/teams` | 必要 | 組織に所属するチーム一覧（ACTIVE のみ）|
| GET | `/api/v1/teams/{id}/organizations` | 必要 | チームが所属する組織一覧（ACTIVE のみ）|
| GET | `/api/v1/teams/{id}/org-invites` | 必要（ADMIN）| 受信した組織招待一覧（PENDING のみ）|
| POST | `/api/v1/teams/{id}/org-invites/{membershipId}/accept` | 必要（ADMIN）| 組織招待を承認（PENDING → ACTIVE）|
| POST | `/api/v1/teams/{id}/org-invites/{membershipId}/reject` | 必要（ADMIN）| 組織招待を拒否（PENDING を削除）|
| DELETE | `/api/v1/teams/{id}/organizations/{orgId}` | 必要（ADMIN）| チームが組織から自主離脱（ACTIVE を削除）|
| GET | `/api/v1/invite/{token}/qr` | 不要 | 招待QRコード画像取得（PNG）|

### リクエスト／レスポンス仕様

#### `POST /api/v1/teams`

**リクエストボディ**
```json
{
  "name": "FCマンシャフト",
  "nickname1": "マンシャフト",
  "nickname2": null,
  "template": "SPORTS",
  "prefecture": "東京都",
  "city": "渋谷区",
  "description": "東京を拠点とするサッカーチーム",
  "visibility": "PUBLIC"
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 1,
    "name": "FCマンシャフト",
    "visibility": "PUBLIC",
    "created_at": "2026-03-01T10:00:00Z"
  }
}
```

---

#### `GET /api/v1/teams/{id}/members`

**認可ルール（visibility 依存）**

| チーム visibility | アクセス可能なユーザー |
|------------------|----------------------|
| `PUBLIC` | 任意の認証済みユーザー |
| `ORGANIZATION_ONLY` | チームメンバー（任意ロール）または所属組織のメンバー（`team_org_memberships.status = 'ACTIVE'` 経由）|
| `PRIVATE` | チームメンバー（任意ロール）のみ。それ以外は 403 |

**返却フィールドのロール別制限**

| 呼び出し者の区分 | 返却フィールド |
|-----------------|--------------|
| ADMIN / DEPUTY_ADMIN（チームメンバー）| 全フィールド（`user_id`, `display_name`, `icon_url`, `role`, `permission_groups`, `joined_at`）|
| MEMBER / SUPPORTER / GUEST（チームメンバー）| 基本プロフィール（`user_id`, `display_name`, `icon_url`, `role`）— `permission_groups` / `joined_at` は返さない |
| 非メンバー（PUBLIC / ORGANIZATION_ONLY への外部アクセス）| 基本プロフィールのみ（MEMBER と同内容）|

> - `permission_groups` は `role = DEPUTY_ADMIN` または `role = MEMBER`（権限グループが1件以上割り当て済み）のユーザーに返す。その他のロールまたは未割り当て MEMBER は空配列
> - 支払い状況・連絡先等の個人情報は本エンドポイントには含めない（F04 参照）。SUPPORTER が自身の関連メンバー（子など）の詳細を閲覧する機能は F04 で設計する

**クエリパラメータ**
| パラメータ | 型 | 説明 |
|-----------|---|------|
| `page` | Int | ページ番号（0始まり）|
| `size` | Int | 1ページ件数（最大100）|

**レスポンス（200 OK）**

※ `joined_at`（参加日時）昇順で返す。

ADMIN / DEPUTY_ADMIN が取得した場合（全フィールド）:
```json
{
  "data": [
    {
      "user_id": 42,
      "display_name": "田中太郎",
      "icon_url": "https://cdn.mannschaft.app/users/42/icon.webp",
      "role": "DEPUTY_ADMIN",
      "permission_groups": [
        {"id": 1, "name": "受付・安否確認"}
      ],
      "joined_at": "2026-03-01T10:00:00Z"
    }
  ],
  "meta": {
    "page": 0,
    "size": 50,
    "total_elements": 24,
    "total_pages": 1,
    "has_next": false
  }
}
```

MEMBER / SUPPORTER / GUEST またはチーム非メンバー（PUBLIC / ORGANIZATION_ONLY）が取得した場合（基本プロフィールのみ）:
```json
{
  "data": [
    {
      "user_id": 42,
      "display_name": "田中太郎",
      "icon_url": "https://cdn.mannschaft.app/users/42/icon.webp",
      "role": "DEPUTY_ADMIN"
    }
  ],
  "meta": {
    "page": 0,
    "size": 50,
    "total_elements": 24,
    "total_pages": 1,
    "has_next": false
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | `visibility = PRIVATE` かつ呼び出し者がチームメンバーでない / `visibility = ORGANIZATION_ONLY` かつ呼び出し者が当該チームのメンバーでも所属組織のメンバーでもない |
| 404 | チームが存在しない / 論理削除済み |

---

#### `POST /api/v1/teams/{id}/invite-tokens`

**リクエストボディ**
```json
{
  "role_id": 4,
  "expires_in": "7d",
  "max_uses": 50
}
```

> `expires_in`: `1d` / `7d` / `30d` / `90d` / `unlimited`（`unlimited` は `expires_at = NULL`）

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 10,
    "token": "550e8400-e29b-41d4-a716-446655440000",
    "invite_url": "https://mannschaft.app/invite/550e8400-e29b-41d4-a716-446655440000",
    "role": "MEMBER",
    "expires_at": "2026-03-08T10:00:00Z",
    "max_uses": 50,
    "used_count": 0
  }
}
```

---

#### `GET /api/v1/invite/{token}`（未認証可）

**レスポンス（200 OK）**

チーム招待の場合:
```json
{
  "data": {
    "invite_type": "TEAM",
    "target": {
      "id": 1,
      "name": "FCマンシャフト",
      "icon_url": "https://cdn.mannschaft.app/teams/1/icon.webp"
    },
    "role": "MEMBER",
    "expires_at": "2026-03-08T10:00:00Z",
    "is_valid": true
  }
}
```

組織招待の場合:
```json
{
  "data": {
    "invite_type": "ORGANIZATION",
    "target": {
      "id": 5,
      "name": "〇〇サッカー協会",
      "icon_url": "https://cdn.mannschaft.app/organizations/5/icon.webp"
    },
    "role": "MEMBER",
    "expires_at": "2026-03-08T10:00:00Z",
    "is_valid": true
  }
}
```

> - `invite_type`: `"TEAM"` または `"ORGANIZATION"`
> - `target.id`: チームまたは組織の ID（`invite_type` で解釈を切り替える）
> - `is_valid = false` の場合は期限切れ・上限到達・手動失効のいずれか（詳細は返さない）
> - `target` は `is_valid = false` でも返す（参加先の名前をUI表示するため）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 404 | トークンが存在しない |
| 429 | レートリミット超過（10 req/min per IP）|

---

#### `GET /api/v1/invite/{token}/qr`（未認証可）

招待URLをエンコードした QR コード画像を返す。`GET /invite/{token}` と同一のアクセス制御を適用する。

**レスポンス（200 OK）**

```
Content-Type: image/png
Body: QRコード PNG バイナリ（invite_url をエンコード・デフォルト 300×300px）
```

> - QR コードにエンコードする値は `https://mannschaft.app/invite/{token}` 形式の invite_url
> - `size` クエリパラメータ（任意・整数・px）でサイズ変更可能（最小64 / 最大1024 / デフォルト300）
> - バックエンドで ZXing ライブラリを使用して動的生成（S3 への保存は行わない）
> - **キャッシュ推奨**: ZXing による PNG 生成は CPU コストが高い。同一トークン・同一サイズへの連続リクエストに備え、生成済み画像を Redis またはオンヒープキャッシュ（`{token}:{size}` をキーに TTL 5分）に保存し、キャッシュヒット時は再生成をスキップする実装を推奨する

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | `size` パラメータが範囲外 |
| 404 | トークンが存在しない |
| 429 | レートリミット超過（10 req/min per IP）|

---

#### `POST /api/v1/invite/{token}/join`

**リクエストボディ**: なし（認証ヘッダーからユーザーを取得）

**レスポンス（200 OK）**

チーム招待の場合:
```json
{
  "data": {
    "invite_type": "TEAM",
    "target": {
      "id": 1,
      "name": "FCマンシャフト"
    },
    "role": "MEMBER"
  }
}
```

組織招待の場合:
```json
{
  "data": {
    "invite_type": "ORGANIZATION",
    "target": {
      "id": 5,
      "name": "〇〇サッカー協会"
    },
    "role": "MEMBER"
  }
}
```

> `invite_type` によりフロントエンドが遷移先（チームダッシュボード or 組織ダッシュボード）を決定する。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | トークン期限切れ・使用回数上限・手動失効 |
| 403 | ブロック済みユーザー（`team_blocks` / `organization_blocks` にエントリが存在）|
| 409 | すでにメンバーとして参加済み |
| 422 | 招待先チーム / 組織がアーカイブ済み（`archived_at IS NOT NULL`）|

---

#### `PUT /api/v1/teams/{id}/members/{userId}/permission-groups`

**リクエストボディ**
```json
{
  "group_ids": [1, 3]
}
```

> 既存の割り当てを一括置換する（差分でなく全上書き）。空配列で全グループ解除。

**レスポンス（200 OK）**
```json
{
  "data": {
    "user_id": 42,
    "permission_groups": [
      {"id": 1, "name": "受付・安否確認"},
      {"id": 3, "name": "スケジュール管理"}
    ]
  }
}
```

**エラーレスポンス（共通）**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 401 | 未認証 |
| 403 | 権限不足 |
| 404 | リソース不存在 |
| 409 | 競合（メンバー重複参加など）|
| 422 | ビジネスロジックエラー（独立チームに ORGANIZATION_ONLY 設定など）|

---

#### `GET /api/v1/organizations/{id}/members`

**認可ルール（visibility 依存）**

| 組織 visibility | アクセス可能なユーザー |
|----------------|----------------------|
| `PUBLIC` | 任意の認証済みユーザー |
| `PRIVATE` | 組織メンバー（任意ロール）のみ。それ以外は 403 |

> 組織の `visibility` は `PUBLIC` / `PRIVATE` の2値のみ（チームの `ORGANIZATION_ONLY` は存在しない）

**返却フィールドのロール別制限**

| 呼び出し者の区分 | 返却フィールド |
|-----------------|--------------|
| ADMIN / DEPUTY_ADMIN（組織メンバー）| 全フィールド（`user_id`, `display_name`, `icon_url`, `role`, `permission_groups`, `joined_at`）|
| MEMBER / SUPPORTER / GUEST（組織メンバー）| 基本プロフィール（`user_id`, `display_name`, `icon_url`, `role`）|
| 非メンバー（PUBLIC 組織への外部アクセス）| 基本プロフィールのみ（MEMBER と同内容）|

> - `permission_groups` は `role = DEPUTY_ADMIN` または `role = MEMBER`（権限グループが1件以上割り当て済み）のユーザーに返す。その他のロールまたは未割り当て MEMBER は空配列
> - 支払い状況・連絡先等の個人情報は本エンドポイントには含めない（F04 参照）

**クエリパラメータ**
| パラメータ | 型 | 説明 |
|-----------|---|------|
| `page` | Int | ページ番号（0始まり）|
| `size` | Int | 1ページ件数（最大100）|

**レスポンス（200 OK）**

※ `joined_at`（参加日時）昇順で返す。レスポンス構造は `GET /api/v1/teams/{id}/members` と同一（`permission_groups` / `joined_at` の返却条件も同様）。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | `visibility = PRIVATE` かつ呼び出し者が組織メンバーでない |
| 404 | 組織が存在しない / 論理削除済み |

---

#### `GET /api/v1/organizations/{id}/members/all`

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `scope` | String | `INDIVIDUAL` | 収集対象の範囲。`ORGANIZATION`（組織直属のみ）/ `TEAM`（チームメンバーのみ）/ `INDIVIDUAL`（全員）|
| `page` | Int | `0` | ページ番号（0始まり）|
| `size` | Int | `50` | 1ページ件数（最大200）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "user_id": 42,
      "display_name": "田中太郎",
      "icon_url": "https://cdn.mannschaft.app/users/42/icon.webp",
      "member_of": {
        "type": "ORGANIZATION",
        "id": 10,
        "name": "1年生"
      },
      "role": "ADMIN"
    },
    {
      "user_id": 55,
      "display_name": "鈴木花子",
      "icon_url": "https://cdn.mannschaft.app/users/55/icon.webp",
      "member_of": {
        "type": "TEAM",
        "id": 3,
        "name": "1年A組"
      },
      "role": "MEMBER"
    }
  ],
  "meta": {
    "page": 0,
    "size": 50,
    "total_elements": 312,
    "total_pages": 7,
    "has_next": true,
    "scope": "INDIVIDUAL"
  }
}
```

> - `member_of.type`: `"ORGANIZATION"` または `"TEAM"`（どのエンティティ経由で所属しているか）
> - カスケード通知の送信前に対象人数・構成を確認する用途を想定

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | `scope` の値が不正 |
| 403 | 権限不足（ADMIN 未満）|
| 404 | 組織が存在しない |

---

#### `GET /api/v1/me/teams`

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `include_archived` | Boolean | `false` | `true` にするとアーカイブ済みチームも含める |
| `page` | Int | `0` | ページ番号（0始まり）|
| `size` | Int | `50` | 1ページ件数（最大100）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 1,
      "name": "FCマンシャフト",
      "icon_url": "https://cdn.mannschaft.app/teams/1/icon.webp",
      "visibility": "PUBLIC",
      "role": "ADMIN",
      "joined_at": "2026-03-01T10:00:00Z",
      "is_archived": false
    },
    {
      "id": 7,
      "name": "渋谷バスケ部",
      "icon_url": null,
      "visibility": "PRIVATE",
      "role": "MEMBER",
      "joined_at": "2026-02-15T09:00:00Z",
      "is_archived": false
    }
  ],
  "meta": {
    "page": 0,
    "size": 50,
    "total_elements": 2,
    "total_pages": 1,
    "has_next": false
  }
}
```

> - 論理削除済みチーム（`teams.deleted_at IS NOT NULL`）は常に除外する
> - `include_archived = false`（デフォルト）の場合、アーカイブ済みチーム（`archived_at IS NOT NULL`）も除外する
> - `role` はそのチームにおける自分のロール名（ADMIN / DEPUTY_ADMIN / MEMBER / SUPPORTER / GUEST）
> - `joined_at` は `user_roles.created_at`（参加日時）
> - 返却順: `joined_at` 昇順

---

#### `GET /api/v1/me/organizations`

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `include_archived` | Boolean | `false` | `true` にするとアーカイブ済み組織も含める |
| `page` | Int | `0` | ページ番号（0始まり）|
| `size` | Int | `50` | 1ページ件数（最大100）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 5,
      "name": "〇〇サッカー協会",
      "icon_url": "https://cdn.mannschaft.app/organizations/5/icon.webp",
      "visibility": "PUBLIC",
      "role": "ADMIN",
      "joined_at": "2026-01-10T08:00:00Z",
      "is_archived": false
    }
  ],
  "meta": {
    "page": 0,
    "size": 50,
    "total_elements": 1,
    "total_pages": 1,
    "has_next": false
  }
}
```

> - 論理削除済み組織（`organizations.deleted_at IS NOT NULL`）は常に除外する
> - `include_archived = false`（デフォルト）の場合、アーカイブ済み組織（`archived_at IS NOT NULL`）も除外する
> - `role` はその組織における自分のロール名
> - `joined_at` は `user_roles.created_at`
> - 返却順: `joined_at` 昇順

**エラーレスポンス（`/me/teams` および `/me/organizations` 共通）**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |

---

#### `GET /api/v1/organizations/{id}/teams`

組織に所属する（`team_org_memberships.status = 'ACTIVE'`）チーム一覧を返す。

**認可ルール**

| 組織 visibility | アクセス可能なユーザー |
|----------------|----------------------|
| `PUBLIC` | 任意の認証済みユーザー |
| `PRIVATE` | 組織メンバー（任意ロール）のみ。それ以外は 403 |

**返却チームの visibility フィルタ**

呼び出し可能な場合でも、個々のチームの `visibility` に応じてレスポンスの内容を制限する:

| チーム visibility | 返却条件 |
|------------------|---------|
| `PUBLIC` | 常に返す |
| `ORGANIZATION_ONLY` | 常に返す（組織所属チームが `ORGANIZATION_ONLY` を選択した意図＝組織コンテキストでの公開を尊重するため）|
| `PRIVATE` | 呼び出し者がそのチームのメンバーの場合のみ返す。非メンバーにはレスポンスから除外（404 にはしない）|

**クエリパラメータ**
| パラメータ | 型 | 説明 |
|-----------|---|------|
| `page` | Int | ページ番号（0始まり）|
| `size` | Int | 1ページ件数（最大100）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 3,
      "name": "FCマンシャフト U-12",
      "icon_url": "https://cdn.mannschaft.app/teams/3/icon.webp",
      "visibility": "PUBLIC"
    }
  ],
  "meta": {
    "page": 0,
    "size": 50,
    "total_elements": 5,
    "total_pages": 1,
    "has_next": false
  }
}
```

> - 論理削除済みチーム（`deleted_at IS NOT NULL`）は常に除外する
> - `total_elements` は visibility フィルタ適用後の件数

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | 組織の `visibility = PRIVATE` かつ呼び出し者が組織メンバーでない |
| 404 | 組織が存在しない / 論理削除済み |

---

#### `GET /api/v1/teams/{id}/organizations`

チームが所属する（`team_org_memberships.status = 'ACTIVE'`）組織一覧を返す。

**認可ルール**

| チーム visibility | アクセス可能なユーザー |
|------------------|----------------------|
| `PUBLIC` / `ORGANIZATION_ONLY` | 任意の認証済みユーザー |
| `PRIVATE` | チームメンバー（任意ロール）のみ。それ以外は 403 |

**返却組織の visibility フィルタ**

| 組織 visibility | 返却条件 |
|----------------|---------|
| `PUBLIC` | 常に返す |
| `PRIVATE` | 呼び出し者がその組織のメンバーの場合のみ返す。非メンバーにはレスポンスから除外（404 にはしない）|

**クエリパラメータ**
| パラメータ | 型 | 説明 |
|-----------|---|------|
| `page` | Int | ページ番号（0始まり）|
| `size` | Int | 1ページ件数（最大100）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 5,
      "name": "〇〇サッカー協会",
      "icon_url": "https://cdn.mannschaft.app/organizations/5/icon.webp",
      "visibility": "PUBLIC"
    }
  ],
  "meta": {
    "page": 0,
    "size": 50,
    "total_elements": 2,
    "total_pages": 1,
    "has_next": false
  }
}
```

> - 論理削除済み組織（`deleted_at IS NOT NULL`）は常に除外する
> - `total_elements` は visibility フィルタ適用後の件数

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | チームの `visibility = PRIVATE` かつ呼び出し者がチームメンバーでない |
| 404 | チームが存在しない / 論理削除済み |

---

#### `PATCH /api/v1/teams/{id}/archive` / `PATCH /api/v1/teams/{id}/unarchive`

リクエストボディなし。204 No Content を返す。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | 権限不足（ADMIN 未満）|
| 404 | チームが存在しない / 論理削除済み |
| 422 | archive: すでにアーカイブ済み / unarchive: アーカイブ状態でない |

---

#### `PATCH /api/v1/organizations/{id}/archive` / `PATCH /api/v1/organizations/{id}/unarchive`

リクエストボディなし。204 No Content を返す。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | 権限不足（ADMIN 未満）|
| 404 | 組織が存在しない / 論理削除済み |
| 422 | archive: すでにアーカイブ済み / unarchive: アーカイブ状態でない |

---

## 5. ビジネスロジック

### チーム作成フロー

```
1. POST /api/v1/teams を受付
2. リクエストボディをバリデーション
3. teams に INSERT
4. user_roles に (user_id=作成者, role_id=ADMIN, team_id=新チームID) を INSERT
5. audit_logs に TEAM_CREATED を記録（team_id = 新チームID）
6. 201 Created を返す
```

> - チームは常に独立した状態で作成される。組織への所属は作成後に組織側からの招待フロー（`POST /organizations/{id}/team-invites`）経由で行う
> - 1つのチームが複数の組織に同時所属することが可能

### 組織作成フロー

```
1. POST /api/v1/organizations を受付
2. parent_organization_id が指定された場合（子組織として作成）:
   a. 親組織が存在・未削除か確認 → 存在しない / 論理削除済みの場合は 404
   b. 操作者が親組織の ADMIN（または SYSTEM_ADMIN）か確認 → ADMIN 未満は 403
   c. 循環参照チェック + 深さチェック（`app.org.max-depth` を参照; 超過時は 422）
3. organizations に INSERT
4. user_roles に (user_id=作成者, role_id=ADMIN, organization_id=新組織ID) を INSERT
5. audit_logs に ORGANIZATION_CREATED を記録（metadata: {"org_type": "NONPROFIT" or "FORPROFIT"}）
6. 201 Created を返す
```

> - `parent_organization_id` を指定しない場合（トップレベル組織）: 認証済みユーザーであれば誰でも作成可

### org_type 変更フロー

`org_type` は自己申告制であり、ADMIN が任意のタイミングで変更できる（SYSTEM_ADMIN の承認不要）。UI 側では NONPROFIT / FORPROFIT を色分け等で視覚的に区別する（フロントエンド実装の責務）。

```
1. PATCH /api/v1/organizations/{id} を受付（body に "org_type" フィールドが含まれる場合に本フローを適用）
2. 操作者が当該組織の ADMIN か確認（ADMIN 未満は 403）
3. org_type の値が NONPROFIT / FORPROFIT のいずれかであることをバリデーション（それ以外は 400）
4. 変更前の org_type と同値であれば 200 OK をそのまま返す（UPDATE 省略）
5. organizations.org_type を UPDATE（即時反映）
6. audit_logs に ORGANIZATION_ORG_TYPE_CHANGED を記録
   metadata: {"before": "FORPROFIT", "after": "NONPROFIT"}
7. 200 OK を返す
```

> 課金ロジックは `org_type` の現在値をそのまま参照する。変更後は次の課金サイクルから新しい種別が適用される（課金機能 feature doc で詳細設計）。

---

### チーム論理削除フロー

```
1. DELETE /api/v1/teams/{id} を受付
2. 操作者が当該チームの ADMIN か確認（ADMIN 未満は 403）
3. teams.deleted_at = NOW() を UPDATE（論理削除）
4. invite_tokens に UPDATE SET revoked_at = NOW()
   WHERE team_id = チームID AND revoked_at IS NULL
   （削除済みチームへの参加導線を即時遮断）
5. team_org_memberships を DELETE WHERE team_id = チームID
   （論理削除では ON DELETE CASCADE が発動しないため明示的に削除）
6. audit_logs に TEAM_DELETED を記録
7. 204 No Content を返す
```

> - `user_roles` はチーム論理削除後も保持する（削除済みチームはアプリ層でフィルタリング）
> - 子チームは存在しない（チームは階層を持たない）ため、カスケード処理は不要
> - チームに紐付く他のデータ（スケジュール・ファイル・投稿等）の扱いは各 feature doc で設計

### 組織論理削除フロー

```
1. DELETE /api/v1/organizations/{id} を受付
2. 操作者が当該組織の ADMIN か確認（ADMIN 未満は 403）
3. organizations.deleted_at = NOW() を UPDATE（論理削除）
4. invite_tokens に UPDATE SET revoked_at = NOW()
   WHERE organization_id = 組織ID AND revoked_at IS NULL
   （削除済み組織への参加導線を即時遮断）
5. team_org_memberships を DELETE WHERE organization_id = 組織ID
   （論理削除では ON DELETE CASCADE が発動しないため明示的に削除。チーム自体は存続）
6. audit_logs に ORGANIZATION_DELETED を記録
7. 204 No Content を返す
```

> - 当該組織に所属する子組織・チームは削除しない（子組織はそのまま存続。チームも独立して存続し引き続き他の組織に所属可能）
> - 子チームの `invite_tokens` は本フローでは失効させない（チームが独立して存続するため）。チームを合わせて削除する場合はチーム削除フローを別途実行する
> - `team_org_memberships` の当該組織エントリは step 5 で明示的に削除する（論理削除では ON DELETE CASCADE が発動しないため）。チームの所属記録は消えるが、チーム自体は独立して存続する
> - `user_roles`（`organization_id` スコープ）は組織論理削除後も保持する（削除済み組織はアプリ層でフィルタリング）

### チーム-組織所属招待フロー

**① 組織からチームへ招待を送信**

```
1. POST /api/v1/organizations/{orgId}/team-invites を受付（body: {"team_id": X}）
2. 組織が存在・未削除か確認 → なければ 404
3. 操作者が当該組織の ADMIN か確認 → ADMIN 未満は 403
4. 招待対象チームが存在・未削除か確認 → なければ 404
5. team_org_memberships に当該 (team_id, organization_id) のエントリが存在するか確認
   → ACTIVE 存在: 409（すでに所属済み）
   → PENDING 存在: 409（招待送信済み）
6. team_org_memberships に INSERT（status = PENDING）
7. チームの ADMIN に通知（通知機能 feature doc 参照）
8. audit_logs に TEAM_ORG_INVITE_SENT を記録
9. 201 Created を返す
```

**② チームが招待を承認**

```
1. POST /api/v1/teams/{teamId}/org-invites/{membershipId}/accept を受付
2. チームが存在・未削除か確認 → なければ 404
3. 操作者が当該チームの ADMIN か確認 → ADMIN 未満は 403
4. team_org_memberships の当該エントリが存在しかつ status = PENDING か確認 → なければ 404
5. team_org_memberships の status = ACTIVE、responded_by、responded_at を UPDATE
6. audit_logs に TEAM_ORG_MEMBERSHIP_CREATED を記録
7. 200 OK を返す
```

**③ チームが招待を拒否**

```
1. POST /api/v1/teams/{teamId}/org-invites/{membershipId}/reject を受付
2. チームが存在・未削除か確認 → なければ 404
3. 操作者が当該チームの ADMIN か確認 → ADMIN 未満は 403
4. team_org_memberships の当該エントリが存在しかつ status = PENDING か確認 → なければ 404
5. team_org_memberships を DELETE（物理削除）
6. audit_logs に TEAM_ORG_INVITE_REJECTED を記録
7. 200 OK を返す
```

**④ 組織が招待を取消**

```
1. DELETE /api/v1/organizations/{orgId}/team-invites/{teamId} を受付
2. 組織が存在・未削除か確認 → なければ 404
3. 操作者が当該組織の ADMIN か確認 → ADMIN 未満は 403
4. team_org_memberships の (team_id, organization_id) PENDING エントリを確認 → なければ 404
5. team_org_memberships を DELETE（物理削除）
6. audit_logs に TEAM_ORG_INVITE_CANCELLED を記録
7. 204 No Content を返す
```

**⑤ チームが組織から自主離脱**

```
1. DELETE /api/v1/teams/{teamId}/organizations/{orgId} を受付
2. チームが存在・未削除か確認 → なければ 404
3. 操作者が当該チームの ADMIN か確認 → ADMIN 未満は 403
4. team_org_memberships の (team_id, organization_id) ACTIVE エントリを確認 → なければ 404
5. team_org_memberships を DELETE（物理削除）
6. audit_logs に TEAM_ORG_MEMBERSHIP_REMOVED を記録（metadata: {"reason": "TEAM_LEFT"}）
7. 204 No Content を返す
```

**⑥ 組織がチームを除名**

```
1. DELETE /api/v1/organizations/{orgId}/teams/{teamId} を受付
2. 組織が存在・未削除か確認 → なければ 404
3. 操作者が当該組織の ADMIN か確認 → ADMIN 未満は 403
4. team_org_memberships の (team_id, organization_id) ACTIVE エントリを確認 → なければ 404
5. team_org_memberships を DELETE（物理削除）
6. audit_logs に TEAM_ORG_MEMBERSHIP_REMOVED を記録（metadata: {"reason": "ORG_REMOVED"}）
7. 204 No Content を返す
```

> - 再招待（拒否・取消後）は新規 INSERT で再開始する（UNIQUE KEY により (team_id, organization_id) のエントリは常に最大1件）
> - 1つのチームが複数組織に同時 ACTIVE 所属することは可能

---

### 招待トークン作成フロー

```
1. POST /api/v1/teams/{id}/invite-tokens（または /organizations/{id}/invite-tokens）を受付
2. チーム/組織が存在・未削除・未アーカイブか確認 → なければ 404 / アーカイブ済みなら 422
3. 操作者が ADMIN か確認（DEPUTY_ADMIN の場合は INVITE_MEMBERS かつ MANAGE_INVITE_TOKENS 権限が必要）
   → 権限不足は 403
4. role_id のバリデーション:
   a. 指定された role_id が roles テーブルに存在するか確認 → なければ 400
   b. 操作者が ADMIN の場合: role_id の priority >= 3（DEPUTY_ADMIN 以下）か確認
      → ADMIN（priority=2）/ SYSTEM_ADMIN（priority=1）のトークン作成は 403
   c. 操作者が SYSTEM_ADMIN の場合: role_id の priority >= 2（ADMIN 以下）まで許可
5. expires_in のバリデーション（1d / 7d / 30d / 90d / unlimited のいずれか）→ 不正値は 400
6. invite_tokens に INSERT（token = UUID v4 生成、expires_at を expires_in から計算）
7. audit_logs に TEAM_INVITE_TOKEN_CREATED または ORGANIZATION_INVITE_TOKEN_CREATED を記録
8. 201 Created を返す
```

> - ADMIN は自分と同等以上のロール（priority <= 2）のトークンを作成できない（ロール昇格制限と同一ポリシー）
> - SYSTEM_ADMIN は ADMIN 以下のトークンを作成可能（SYSTEM_ADMIN 自身のトークン作成は不可・プラットフォームスコープのため招待対象外）

### 招待参加フロー

```
1. GET /api/v1/invite/{token} でプレビュー（オプション）
2. POST /api/v1/invite/{token}/join を受付
3. invite_tokens を SELECT ... FOR UPDATE で取得（排他ロック）
4. 有効性チェック: revoked_at IS NULL かつ
   (expires_at IS NULL OR expires_at > NOW()) かつ
   (max_uses IS NULL OR used_count < max_uses)
   → いずれか失敗で 400
5. 招待先チーム/組織のアーカイブチェック:
   招待先エンティティの archived_at IS NOT NULL → 422（アーカイブ済みのため参加不可）
6. ブロック済みチェック:
   - チーム招待（invite_tokens.team_id IS NOT NULL）の場合:
     team_blocks に (team_id = invite_tokens.team_id, user_id = current_user_id) が存在すれば 403
   - 組織招待（invite_tokens.organization_id IS NOT NULL）の場合:
     organization_blocks に (organization_id = invite_tokens.organization_id, user_id = current_user_id) が存在すれば 403
7. user_roles に当該スコープのエントリが既存か確認 → 存在すれば 409
8. user_roles に INSERT（role_id = invite_tokens.role_id）
9. invite_tokens.used_count を +1 UPDATE
10. audit_logs に TEAM_MEMBER_JOINED または ORGANIZATION_MEMBER_JOINED を記録
11. 200 OK を返す
```

### ロール変更フロー

```
1. PATCH /api/v1/teams/{id}/members/{userId}/role を受付
2. 操作者が対象チームの ADMIN か確認
3. 変更先ロールが priority >= 3（DEPUTY_ADMIN 以下）か確認
   → ADMIN への昇格（priority <= 2）は SYSTEM_ADMIN のみ
4. 【2FA必須チェック】変更先ロールが ADMIN の場合:
   two_factor_auth に対象ユーザーの有効な TOTP レコードが存在するか確認
   → 存在しない（2FA 未設定）であれば 422（「ADMIN ロールには2FA設定が必要です」）
5. 【最後のADMIN保護】変更先ロールが ADMIN でない場合:
   user_roles WHERE team_id = X AND role_id = ADMIN の件数を確認
   → 1件（対象ユーザーのみ）であれば 422（「最後の管理者は降格できません」）
6. user_roles を UPDATE（role_id を変更）
7. 権限グループのクリーンアップ:
   - 変更前ロールが DEPUTY_ADMIN かつ変更後ロールが MEMBER の場合:
     `user_permission_groups` のうち `target_role = 'DEPUTY_ADMIN'` のグループ割り当てを削除（MEMBER 用グループは存在しないためクリア後は割り当てなし）
   - 変更前ロールが MEMBER かつ変更後ロールが DEPUTY_ADMIN の場合:
     `user_permission_groups` のうち `target_role = 'MEMBER'` のグループ割り当てを削除（DEPUTY_ADMIN 用グループへの再割り当ては ADMIN が別途実施）
   - 変更後ロールが DEPUTY_ADMIN でも MEMBER でもない場合（SUPPORTER / GUEST 等）:
     `user_permission_groups` を全削除
   - 変更後ロールが変更前と同一の場合: 保持
8. audit_logs に TEAM_MEMBER_ROLE_CHANGED を記録（target_user_id = 対象ユーザー）
9. 200 OK を返す
```

### メンバー除名フロー

```
1. DELETE /api/v1/teams/{id}/members/{userId} を受付
2. 操作者が対象チームの ADMIN か確認
3. 【最後のADMIN保護】対象ユーザーのロールが ADMIN の場合:
   user_roles WHERE team_id = X AND role_id = ADMIN の件数を確認
   → 1件（対象ユーザーのみ）であれば 422（「最後の管理者は除名できません」）
4. 対象ユーザーの user_roles（team_id スコープ）を DELETE
5. user_permission_groups（当該チームのグループ）を DELETE
6. audit_logs に TEAM_MEMBER_REMOVED を記録（target_user_id = 対象ユーザー）
7. 204 No Content を返す
```

### 権限解決ロジック

リクエストごとに以下の手順で実効パーミッションを決定する:

```
1. JWT から user_id を取得
2. JWT の is_system_admin = true → SYSTEM_ADMIN として全権限付与（DB 参照不要）
3. 対象 team_id / organization_id に対応する user_roles を SELECT
4. ロールが ADMIN →
     role_permissions WHERE role_id = ADMIN から全パーミッションを取得
5. ロールが DEPUTY_ADMIN →
     user_permission_groups（当該ユーザー・スコープ）
       → permission_group_permissions
       → permissions
     を結合して実効パーミッションセットを取得
     ※ role_permissions は参照しない（権限グループ未割り当ての場合は実効パーミッション 0）
     ※ スコープ: team_id スコープなら permission_groups.team_id = チームID で絞り込み
               organization_id スコープなら permission_groups.organization_id = 組織ID で絞り込み
6. ロールが MEMBER →
   a. user_permission_groups（当該ユーザー・スコープ）に割り当てグループが存在するか確認
   b. 割り当てグループなし →
        role_permissions WHERE role_id = MEMBER AND is_default = TRUE（基本3件）を実効パーミッションとして取得
   c. 割り当てグループあり（1件以上）→
        is_default を完全に無視し、user_permission_groups
          → permission_groups WHERE target_role = 'MEMBER'（AND team_id/organization_id でスコープ絞り込み）
          → permission_group_permissions → permissions
        の UNION のみを実効パーミッションとする（グループに基本3件が含まれていなければそれらも失われる）
        ※ ADMIN がお知らせ権限だけ追加したい場合は MANAGE_SCHEDULES/MANAGE_FILES/MANAGE_POSTS も含むグループを作成する必要がある
7. ロールが SUPPORTER / GUEST → パーミッションなし（ロールチェックのみで制御）
8. パーミッションセットを元に操作可否を判定
```

### サポーター登録フロー（フォロー）

```
1. POST /api/v1/teams/{id}/follow を受付
2. チームが存在・未削除・未アーカイブか確認
3. teams.visibility = 'PUBLIC' か確認（ORGANIZATION_ONLY / PRIVATE は 403）
4. teams.supporter_enabled = TRUE か確認 → FALSE で 403
5. team_blocks に user_id が存在するか確認 → 存在すれば 403（ブロック済み）
6. user_roles に当該チームの既存エントリがあるか確認 → 存在すれば 409（既に何らかのロールで所属済み）
7. user_roles に (user_id=リクエスト者, role_id=SUPPORTER, team_id=チームID) を INSERT
8. audit_logs に TEAM_MEMBER_JOINED を記録（metadata: {"join_method": "FOLLOW"}）
9. 200 OK を返す
```

### フォロー解除フロー

```
1. DELETE /api/v1/teams/{id}/follow を受付
2. user_roles に SUPPORTER ロールのエントリが存在するか確認 → なければ 404
3. user_roles を DELETE
4. audit_logs に TEAM_MEMBER_REMOVED を記録（metadata: {"reason": "UNFOLLOW"}）
5. 204 No Content を返す
```

### ブロックフロー

```
1. POST /api/v1/teams/{id}/blocks を受付（body: {"user_id": 42}）
2. 操作者が ADMIN、または REMOVE_MEMBERS 権限を持つ DEPUTY_ADMIN か確認
3. 対象ユーザーが自分自身でないか確認
4. 対象ユーザーが ADMIN / SYSTEM_ADMIN でないか確認（上位ロールはブロック不可）
5. 対象ユーザーが当該チームのメンバー（user_roles にエントリあり）であれば user_roles を DELETE（自動除名）
6. user_permission_groups（当該チームのグループに紐付く対象ユーザーの割り当て）を DELETE
7. team_blocks に INSERT（既にブロック済みの場合は 409）
8. audit_logs に TEAM_MEMBER_BLOCKED を記録（target_user_id = 対象ユーザー）
9. 204 No Content を返す
```

### ブロック解除フロー（チーム）

```
1. DELETE /api/v1/teams/{id}/blocks/{userId} を受付
2. 操作者が ADMIN、または REMOVE_MEMBERS 権限を持つ DEPUTY_ADMIN か確認
3. team_blocks に当該ユーザーのレコードが存在するか確認 → なければ 404
4. team_blocks から DELETE
5. audit_logs に TEAM_MEMBER_UNBLOCKED を記録（target_user_id = 対象ユーザー）
6. 204 No Content を返す
```

### 組織ロール変更・除名フローの最後のADMIN保護

組織メンバーのロール変更（`PATCH /organizations/{id}/members/{userId}/role`）および除名（`DELETE /organizations/{id}/members/{userId}`）は、チームフローと同一の手順を適用する。**2FA必須チェック・最後のADMIN保護チェックも同様に必須**:

- ロール変更: ADMIN 昇格時は対象ユーザーの 2FA 有効状態を確認（未設定なら 422）。`user_roles WHERE organization_id = X AND role_id = ADMIN` が1件のみの場合、その ADMIN の降格を 422 で拒否
- 除名: 対象ユーザーが唯一の ADMIN の場合、除名を 422 で拒否

---

### 組織サポーター登録フロー（フォロー）

```
1. POST /api/v1/organizations/{id}/follow を受付
2. 組織が存在・未削除・未アーカイブか確認
3. organizations.visibility = 'PUBLIC' か確認（PRIVATE は 403）
4. organizations.supporter_enabled = TRUE か確認 → FALSE で 403
5. organization_blocks に user_id が存在するか確認 → 存在すれば 403（ブロック済み）
6. user_roles に当該組織の既存エントリがあるか確認 → 存在すれば 409
7. user_roles に (user_id=リクエスト者, role_id=SUPPORTER, organization_id=組織ID) を INSERT
8. audit_logs に ORGANIZATION_MEMBER_JOINED を記録（metadata: {"join_method": "FOLLOW"}）
9. 200 OK を返す
```

### 組織フォロー解除フロー

```
1. DELETE /api/v1/organizations/{id}/follow を受付
2. user_roles に当該組織の SUPPORTER エントリが存在するか確認 → なければ 404
3. user_roles を DELETE
4. audit_logs に ORGANIZATION_MEMBER_REMOVED を記録（metadata: {"reason": "UNFOLLOW"}）
5. 204 No Content を返す
```

### 組織ブロックフロー

```
1. POST /api/v1/organizations/{id}/blocks を受付（body: {"user_id": 42}）
2. 操作者が ADMIN、または REMOVE_MEMBERS 権限を持つ DEPUTY_ADMIN か確認
3. 対象ユーザーが自分自身・ADMIN・SYSTEM_ADMIN でないか確認
4. 対象ユーザーが当該組織のメンバー（user_roles にエントリあり）であれば user_roles を DELETE（自動除名）
5. user_permission_groups（当該組織のグループに紐付く対象ユーザーの割り当て）を DELETE
6. organization_blocks に INSERT（既にブロック済みの場合は 409）
7. audit_logs に ORGANIZATION_MEMBER_BLOCKED を記録（target_user_id = 対象ユーザー）
8. 204 No Content を返す
```

### ブロック解除フロー（組織）

```
1. DELETE /api/v1/organizations/{id}/blocks/{userId} を受付
2. 操作者が ADMIN、または REMOVE_MEMBERS 権限を持つ DEPUTY_ADMIN か確認
3. organization_blocks に当該ユーザーのレコードが存在するか確認 → なければ 404
4. organization_blocks から DELETE
5. audit_logs に ORGANIZATION_MEMBER_UNBLOCKED を記録（target_user_id = 対象ユーザー）
6. 204 No Content を返す
```

### 自主退会フロー（チーム）

```
1. DELETE /api/v1/teams/{id}/me を受付
2. user_roles に当該チームの自ユーザーエントリが存在するか確認 → なければ 404
3. ロールが SUPPORTER の場合 → 422（「SUPPORTER のフォロー解除は DELETE /api/v1/teams/{id}/follow を使用してください」）
4. 【最後のADMIN保護】ロールが ADMIN の場合:
   user_roles WHERE team_id = X AND role_id = ADMIN の件数を確認
   → 1件（自分のみ）であれば 422（「最後の管理者のため退会できません。先に別のメンバーを ADMIN に昇格させるか、チームを削除してください」）
5. user_roles を DELETE（team_id スコープ）
6. user_permission_groups（当該チームのグループに紐付く自ユーザーの割り当て）を DELETE
7. audit_logs に TEAM_MEMBER_LEFT を記録（metadata: {"reason": "SELF_DEPARTURE"}）
8. 支払いデータ（member_payments 等）は削除しない（F04 詳細設計参照）
9. 204 No Content を返す
```

> - 再参加した場合は「新規加入」として扱う。以前の支払い有効期限の引き継ぎ等は F04 で設計する
> - チームへの直接所属のみ対象（組織経由の子チームへの所属は変更しない）

### 自主退会フロー（組織）

```
1. DELETE /api/v1/organizations/{id}/me を受付
2. user_roles に当該組織の自ユーザーエントリが存在するか確認 → なければ 404
3. ロールが SUPPORTER の場合 → 422（「SUPPORTER のフォロー解除は DELETE /api/v1/organizations/{id}/follow を使用してください」）
4. 【最後のADMIN保護】ロールが ADMIN の場合:
   user_roles WHERE organization_id = X AND role_id = ADMIN の件数を確認
   → 1件（自分のみ）であれば 422（「最後の管理者のため退会できません。先に別のメンバーを ADMIN に昇格させるか、組織を削除してください」）
5. user_roles を DELETE（organization_id スコープ）
6. user_permission_groups（当該組織のグループに紐付く自ユーザーの割り当て）を DELETE
7. audit_logs に ORGANIZATION_MEMBER_LEFT を記録（metadata: {"reason": "SELF_DEPARTURE"}）
8. 支払いデータ（member_payments 等）は削除しない（F04 詳細設計参照）
9. 204 No Content を返す
```

> - 組織退会は組織直属のロール（`user_roles.organization_id`）のみ削除する。当該組織配下のチームへの所属（`user_roles.team_id`）は独立して残る。組織と配下チームの両方から抜けたい場合はそれぞれ個別に操作する
> - 再参加・支払いデータの扱いはチームフローに同じく F04 参照

**エラーレスポンス（自主退会共通）**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 404 | 対象チーム/組織に所属していない |
| 422 | SUPPORTER が `/me` を呼んだ（`/follow` を案内）|
| 422 | 唯一の ADMIN が退会しようとした（先に昇格または削除を促す）|

---

### DEPUTY_ADMIN / MEMBER 権限の 3 層制御

README 記載の 3 層制御は DEPUTY_ADMIN と MEMBER の両ロールに適用される。チームスコープ・組織スコープともに同一の仕組みを使用する（`permission_groups.team_id` または `permission_groups.organization_id` でスコープを区別）。

**DEPUTY_ADMIN の 3 層制御**

1. **SYSTEM_ADMIN が天井を設定**: `role_permissions WHERE role_id = DEPUTY_ADMIN AND is_default = FALSE`（Phase 2 時点で11件、Phase 3 以降 12件）が付与可能な上限。ADMIN は天井に含まれるパーミッションのみを権限グループに追加できる
2. **ADMIN が権限グループを構成**: `permission_groups（target_role = 'DEPUTY_ADMIN', team_id/organization_id = スコープID）` + `permission_group_permissions` で天井内のパーミッションを選択して名前付きグループを作成
3. **ADMIN がユーザーへ割り当て**: `user_permission_groups` で DEPUTY_ADMIN ユーザーと権限グループを紐付け（未割り当て = 実効パーミッション 0）

**MEMBER の 3 層制御**

1. **SYSTEM_ADMIN が天井を設定**: `role_permissions WHERE role_id = MEMBER`（is_default 問わず全6件）が権限グループに含められる上限
2. **ADMIN が権限グループを構成**: `permission_groups（target_role = 'MEMBER', team_id/organization_id = スコープID）` + `permission_group_permissions` で天井内のパーミッションを選択してグループを作成
3. **ADMIN がユーザーへ割り当て**: `user_permission_groups` で対象 MEMBER と権限グループを紐付け
   - 未割り当て → `is_default = TRUE` の基本3件のみが実効パーミッション
   - 1件以上割り当て → グループ内権限の UNION のみが実効パーミッション（基本3件を含むかどうかはグループ定義次第）

> **オーバーライドモデル**: グループが1件以上割り当てられると `is_default` は無視され、グループ内権限のみが実効パーミッションとなる。これにより「ADMIN がグループを割り当てるだけでデフォルト権限を含む完全な権限セットを上書き設定できる」設計になっている。権限を絞りたい場合は基本3件を含まないグループを割り当てればよく、マイナス計算のロジックが不要。
>
> **`DELETE_OTHERS_CONTENT` の扱い**: DEPUTY_ADMIN / MEMBER 双方の天井に含める。いかなるデフォルト権限グループにも含めない。ADMIN が意図的に付与した場合のみ有効。

### MEMBER 権限グループ設定フロー

**例1: MANAGE_ANNOUNCEMENTS を追加しつつ基本権限も維持したい場合**
```
1. ADMIN が MEMBER 用権限グループを作成
   POST /api/v1/teams/{id}/permission-groups  body: { "target_role": "MEMBER", "name": "お知らせ編集担当" }
2. 権限グループにパーミッションを設定（基本3件 + MANAGE_ANNOUNCEMENTS を含める）
   PATCH /api/v1/teams/{id}/permission-groups/{groupId}
   body: { "permission_ids": [MANAGE_SCHEDULES, MANAGE_FILES, MANAGE_POSTS, MANAGE_ANNOUNCEMENTS の各 id] }
3. 対象 MEMBER に権限グループを割り当て
   PUT /api/v1/teams/{id}/members/{userId}/permission-groups  body: { "group_ids": [groupId] }
4. 権限解決ロジックがグループ内パーミッション（4件）のみを実効権限として返す
5. audit_logs に TEAM_MEMBER_PERMISSION_GROUP_ASSIGNED を記録
```

**例2: 特定 MEMBER を制限（読み取り専用に近い状態）にしたい場合**
```
1. ADMIN が空（または最小限）の権限グループを作成・割り当て
   POST /api/v1/teams/{id}/permission-groups  body: { "target_role": "MEMBER", "name": "閲覧専用" }
2. パーミッションを設定しない（または最小限のみ）
3. 対象 MEMBER に割り当て → グループ未設定 MEMBER でデフォルト3件が消え実効権限0（または最小限）に
4. audit_logs に TEAM_MEMBER_PERMISSION_GROUP_ASSIGNED を記録
```

---

### 組織階層とカスケード通知

#### 階層構造

`organizations.parent_organization_id`（自己参照 FK）により任意深さの組織ツリーを構成する。上限は `app.org.max-depth` 設定値（デフォルト: 5）で管理する。スキーマを変更することなく設定値の変更だけで深さを調整可能にするための設計。

```
例（全国規模連盟 / depth 0〜4）:
  全国協会（組織 / depth 0）
    └── 関東支部（組織 / depth 1）
          └── 東京都連盟（組織 / depth 2）
                └── 渋谷区支部（組織 / depth 3）
                      └── 渋谷FC（組織 / depth 4）
                            └── 渋谷FCユース（チーム）── 個人メンバー
```

- **循環参照防止**: 親組織設定時にアプリ層で祖先を遡り、自組織が含まれないことを確認する
- **最大深さ**: `app.org.max-depth`（デフォルト: 5）。depth が `max-depth - 1` の組織を親とする組織の作成は 422 エラー。チームは depth に関係なく任意の組織に所属可
- **深度チェック**: 組織作成・`parent_organization_id` 更新時に祖先を遡って depth を計算し、`max-depth` を超える場合は 422（`"組織階層の最大深さを超えています"`）を返す

#### カスケード通知フロー

通知発行時に2つのスコープを独立して指定することで、**プッシュ通知の宛先**と**掲示板への表示範囲**をそれぞれ制御できる。

**① `notification_scope`（プッシュ通知の宛先範囲）**

| 値 | 収集対象 | ユースケース例 |
|----|---------|-------------|
| `ORGANIZATION` | サブツリー内の**組織に直接所属するメンバー**のみ | 学年の代表・管理者に連絡し、クラス内周知は各組織に任せる |
| `TEAM` | サブツリー内の**チームに所属するメンバー**のみ | クラスの全生徒・部員に直接プッシュ送信 |
| `INDIVIDUAL` | 組織直属 ＋ チームメンバー（全員）| 全関係者に一括プッシュ送信 |

**② `announcement_scope`（お知らせ掲示板への表示範囲）**

| 値 | 掲示板への投稿先 | ユースケース例 |
|----|---------------|-------------|
| `SELF` | 送信元組織の掲示板のみ（子には伝搬しない）| 組織内部向けの連絡 |
| `ORGANIZATIONS` | 送信元組織 ＋ サブツリー内の全子組織の掲示板 | 学校→各学年の掲示板に表示（クラスには出さない）|
| `TEAMS` | 送信元組織 ＋ サブツリー内の全組織 ＋ 全チームの掲示板 | 学校→学年→クラス全掲示板に表示 |

```
1. ADMIN（または SEND_SAFETY_CONFIRMATION / MANAGE_ANNOUNCEMENTS 権限を持つ DEPUTY_ADMIN）が
   上位組織から一括通知を発行し、以下を指定:
   - notification_scope（ORGANIZATION / TEAM / INDIVIDUAL）: プッシュ通知の宛先範囲
   - announcement_scope（SELF / ORGANIZATIONS / TEAMS）: お知らせ掲示板への表示範囲
2. WITH RECURSIVE CTE で対象組織のサブツリー（全子組織 ID）を再帰取得
3. [プッシュ通知] notification_scope に応じてメンバーを収集（下記 CTE 参照）
4. [掲示板投稿] announcement_scope に応じて投稿先エンティティリストを収集し、お知らせレコードを作成
   - SELF        → 起点組織のみ
   - ORGANIZATIONS → サブツリー内の全組織
   - TEAMS       → サブツリー内の全組織 ＋ 全チーム
5. 通知サービスに user_id リストを渡して一括プッシュ送信
6. audit_logs に発行組織・notification_scope・announcement_scope・対象メンバー数を記録
```

**MySQL 8.0 WITH RECURSIVE CTE（スコープ別メンバー取得）**

```sql
WITH RECURSIVE org_subtree AS (
  -- ベースケース: 起点組織
  SELECT id, 0 AS depth
  FROM organizations
  WHERE id = :rootOrgId AND deleted_at IS NULL

  UNION ALL

  -- 再帰ケース: 子組織を追加（:maxDepth - 1 まで; app.org.max-depth を Spring 側で bind）
  SELECT o.id, ot.depth + 1
  FROM organizations o
  INNER JOIN org_subtree ot ON o.parent_organization_id = ot.id
  WHERE o.deleted_at IS NULL AND ot.depth < :maxDepth - 1
)

-- scope = ORGANIZATION: 組織直属メンバーのみ（チームメンバーは含めない）
SELECT DISTINCT ur.user_id FROM user_roles ur
WHERE ur.organization_id IN (SELECT id FROM org_subtree);

-- scope = TEAM: 所属チームメンバーのみ（組織直属は含めない）
-- チームと組織の関係は team_org_memberships（status = ACTIVE）で解決する
SELECT DISTINCT ur.user_id FROM user_roles ur
INNER JOIN teams t ON ur.team_id = t.id
INNER JOIN team_org_memberships tom
  ON t.id = tom.team_id AND tom.status = 'ACTIVE'
WHERE tom.organization_id IN (SELECT id FROM org_subtree)
  AND t.deleted_at IS NULL AND t.archived_at IS NULL;

-- scope = INDIVIDUAL: 上記2クエリの UNION（全員）
SELECT DISTINCT ur.user_id FROM user_roles ur
WHERE ur.organization_id IN (SELECT id FROM org_subtree)
UNION
SELECT DISTINCT ur.user_id FROM user_roles ur
INNER JOIN teams t ON ur.team_id = t.id
INNER JOIN team_org_memberships tom
  ON t.id = tom.team_id AND tom.status = 'ACTIVE'
WHERE tom.organization_id IN (SELECT id FROM org_subtree)
  AND t.deleted_at IS NULL AND t.archived_at IS NULL;
```

> **パフォーマンス注意**: 大規模組織（数百チーム・数千メンバー）への同期実行はタイムアウトのリスクあり。通知発行は Spring `@Async` または MQ を使った非同期処理を推奨（詳細は通知機能 feature doc で設計）。

#### お知らせ掲示板への伝搬

`announcement_scope` は**送信時に1度だけ決定**し、お知らせレコードとともに保存する。各組織/チームの掲示板ロード時に、上位組織からの伝搬対象かどうかを動的に判定する（プルモデル）。

**掲示板表示の解決ロジック（お知らせ機能で実装・F03 はスコープ定義のみ規定）**

```
組織/チームの掲示板ロード時:
1. 当該エンティティ自身が発行したお知らせを取得
2. WITH RECURSIVE で祖先組織を上方向に遡り、各祖先のお知らせを取得
3. 各お知らせの announcement_scope に基づき表示可否を判定:
   - SELF        → 発行元エンティティのみに表示（子孫には表示しない）
   - ORGANIZATIONS → 発行元 + 子孫「組織」に表示（チームには表示しない）
   - TEAMS       → 発行元 + 子孫「組織」「チーム」すべてに表示
4. 表示対象のお知らせを発行日時降順でマージして返す
```

**スコープ × 表示先エンティティの対応表**

| announcement_scope | 発行元組織 | 子組織 | 子チーム |
|-------------------|:---:|:---:|:---:|
| `SELF` | ✓ | - | - |
| `ORGANIZATIONS` | ✓ | ✓ | - |
| `TEAMS` | ✓ | ✓ | ✓ |

> `announcement_scope` は通知発行後に変更不可とする（変更すると掲示板の表示/非表示が事後に変わり、ユーザーの混乱を招くため）。

#### `hierarchy_visibility` による上位組織閲覧制御

子組織・チームのメンバーが上位組織を参照する場合（`GET /api/v1/organizations/{id}`）、上位組織の `hierarchy_visibility` に従って返却内容を制限する。

| hierarchy_visibility | 返却内容（リクエスト者が子孫メンバーの場合）|
|---------------------|------------------------------------------|
| `NONE` | 404（組織の存在自体を露出しない）|
| `BASIC` | `id` / `name` / `description` / `icon_url` のみ |
| `FULL` | `visibility` 設定に従った通常レスポンス |

- 組織に**直接所属**しているメンバー（`user_roles.organization_id = 対象組織`）には `hierarchy_visibility` は影響しない
- 外部ユーザー（どの子孫にも所属していない）には `visibility` による通常制御を適用

```
閲覧制御判定フロー:
1. GET /api/v1/organizations/{targetOrgId} を受付
2. リクエスト者が targetOrgId に直接所属（user_roles に当該組織エントリあり）
   → visibility に従い通常レスポンス
3. リクエスト者が targetOrgId の子孫（子組織または子チーム）のメンバー:
   a. hierarchy_visibility = NONE  → 404
   b. hierarchy_visibility = BASIC → id / name / description / icon_url のみ返却
   c. hierarchy_visibility = FULL  → visibility に従い通常レスポンス
4. いずれにも該当しない外部ユーザー → visibility による通常制御
```

---

### 自動アーカイブバッチ（チーム）

**スケジュール**: 毎月1日 03:00 JST（Spring `@Scheduled(cron = "0 0 3 1 * *")`）

**アーカイブ判定条件**: チームに所属する全メンバー（任意ロール・`user_roles.team_id = 対象チームID`）の最終ログイン日時（`users.last_login_at`）のうち最大値が 12ヶ月以上前である場合にアーカイブ対象とする。

> - `users.last_login_at` は F02 スコープのカラム。本バッチは F02 テーブルをクロスフィーチャーで参照する
> - `last_login_at` が NULL のユーザー（ログイン記録なし）は `COALESCE(last_login_at, '1970-01-01')` で処理し、未ログインユーザーを最も古い日時として扱う
> - SUPPORTER / GUEST を含む全ロールのメンバーを対象とする（「アクティブな関係者」の基準を広く取る）

**バッチ SQL**（概略）:
```sql
UPDATE teams t
SET archived_at = NOW()
WHERE t.archived_at IS NULL
  AND t.deleted_at IS NULL
  AND (
    SELECT COALESCE(MAX(u.last_login_at), '1970-01-01')
    FROM user_roles ur
    JOIN users u ON u.id = ur.user_id
    WHERE ur.team_id = t.id
  ) < DATE_SUB(NOW(), INTERVAL 12 MONTH);
```

**バッチフロー**:
```
1. 上記 SQL で対象チームを一括 UPDATE（archived_at = NOW()）
2. 対象チームの invite_tokens を一括失効:
     UPDATE invite_tokens SET revoked_at = NOW()
     WHERE team_id IN (...対象チームID...) AND revoked_at IS NULL
3. audit_logs に TEAM_ARCHIVED（reason: AUTO_INACTIVE）を件数分 INSERT
4. バッチ実行ログにアーカイブ件数を記録
```

> バッチの排他制御は ShedLock 等の分散ロックライブラリで保証する（複数インスタンス構成での二重実行を防止）

---

### 自動アーカイブバッチ（組織）

**スケジュール**: チームバッチと同一（毎月1日 03:00 JST）。チームバッチの完了後に実行する。

**アーカイブ判定条件**: 以下の**すべて**を満たす組織をアーカイブ対象とする。

| # | 条件 |
|---|------|
| C1 | 組織に**直接所属する全メンバー**（`user_roles.organization_id = 対象組織ID`）の最終ログイン最大値が 12ヶ月以上前 |
| C2 | 組織に **ACTIVE 所属する全チーム**（`team_org_memberships.status = 'ACTIVE'`）の**全メンバー**の最終ログイン最大値が 12ヶ月以上前 |
| C3 | 当該組織に関連する**有効な支払い**（`member_payments.valid_until >= CURDATE()` または `valid_until IS NULL`）が存在しない（F04 クロスフィーチャー参照）|

> - C1・C2 の `last_login_at` NULL 処理は `COALESCE(last_login_at, '1970-01-01')` で統一
> - 子組織（`parent_organization_id` で連なる下位組織）はカスケード対象外。子組織は独自の条件でバッチ判定される
> - `member_payments` は F04 スコープ。F04 設計完了まで C3 はバッチに組み込まず、C1・C2 のみで先行実装可

**バッチ SQL**（概略）:
```sql
-- Step 1: 対象組織 ID リストを取得
SELECT o.id FROM organizations o
WHERE o.archived_at IS NULL
  AND o.deleted_at IS NULL
  -- C1: 直接所属メンバー全員が 12ヶ月超ログインなし
  AND NOT EXISTS (
    SELECT 1 FROM user_roles ur
    JOIN users u ON u.id = ur.user_id
    WHERE ur.organization_id = o.id
      AND COALESCE(u.last_login_at, '1970-01-01') >= DATE_SUB(NOW(), INTERVAL 12 MONTH)
  )
  -- C2: ACTIVE 所属チームの全メンバーも 12ヶ月超ログインなし
  AND NOT EXISTS (
    SELECT 1 FROM user_roles ur
    JOIN users u ON u.id = ur.user_id
    JOIN team_org_memberships tom
      ON tom.team_id = ur.team_id AND tom.status = 'ACTIVE'
    WHERE tom.organization_id = o.id
      AND COALESCE(u.last_login_at, '1970-01-01') >= DATE_SUB(NOW(), INTERVAL 12 MONTH)
  )
  -- C3: 有効な支払いなし（F04 確定後に追加）
  -- AND NOT EXISTS (
  --   SELECT 1 FROM member_payments mp
  --   JOIN payment_items pi ON pi.id = mp.payment_item_id
  --   WHERE pi.organization_id = o.id
  --     AND mp.status = 'PAID'
  --     AND (mp.valid_until IS NULL OR mp.valid_until >= CURDATE())
  -- )
;
```

**バッチフロー**:
```
1. 上記 SQL で対象組織 ID リストを取得
2. organizations.archived_at = NOW() を一括 UPDATE
3. カスケードアーカイブ対象チームを抽出:
     「対象組織ID にのみ ACTIVE 所属し、他に ACTIVE な org 所属を持たないチーム」
     （複数組織に所属するチームはカスケード対象外・他組織配下での継続稼働を保護）
4. 対象チームの teams.archived_at = NOW() を一括 UPDATE
5. 対象組織・カスケードチームの invite_tokens を一括失効:
     UPDATE invite_tokens SET revoked_at = NOW()
     WHERE (organization_id IN (...対象組織ID...)
        OR team_id IN (...カスケードチームID...))
     AND revoked_at IS NULL
6. audit_logs に ORGANIZATION_ARCHIVED（reason: AUTO_INACTIVE）を件数分 INSERT
7. カスケードアーカイブされたチームの audit_logs に
     TEAM_ARCHIVED（reason: AUTO_CASCADE_ORG）を件数分 INSERT
8. バッチ実行ログに組織・チームそれぞれのアーカイブ件数を記録
```

---

### 手動アーカイブフロー

**チームの場合:**
```
1. PATCH /api/v1/teams/{id}/archive を受付
2. ADMIN 権限チェック → ADMIN 未満は 403
3. archived_at IS NOT NULL → すでにアーカイブ済み → 422
4. teams.archived_at = NOW() で UPDATE
5. invite_tokens を一括失効:
     UPDATE invite_tokens SET revoked_at = NOW()
     WHERE team_id = 対象ID AND revoked_at IS NULL
6. audit_logs に TEAM_ARCHIVED（reason: MANUAL）を記録
7. 204 No Content を返す
```

**組織の場合（カスケードあり）:**
```
1. PATCH /api/v1/organizations/{id}/archive を受付
2. ADMIN 権限チェック → ADMIN 未満は 403
3. archived_at IS NOT NULL → すでにアーカイブ済み → 422
4. organizations.archived_at = NOW() で UPDATE
5. カスケードアーカイブ対象チームを抽出:
     「この組織にのみ ACTIVE 所属し、他に ACTIVE な org 所属を持たないチーム」
     ※ 複数組織に所属するチームはカスケード対象外
6. 対象チームの teams.archived_at = NOW() を UPDATE
7. 組織・カスケードチームの invite_tokens を一括失効:
     UPDATE invite_tokens SET revoked_at = NOW()
     WHERE (organization_id = 対象ID OR team_id IN (...カスケードチームID...))
     AND revoked_at IS NULL
8. audit_logs に ORGANIZATION_ARCHIVED（reason: MANUAL）を記録
9. カスケードされたチームの audit_logs に TEAM_ARCHIVED（reason: MANUAL_CASCADE_ORG）を記録
10. 204 No Content を返す
```

---

### アーカイブ解除フロー（チーム / 組織共通）

```
1. PATCH /api/v1/teams/{id}/unarchive（または /organizations/{id}/unarchive）を受付
2. ADMIN 権限チェック → ADMIN 未満は 403
3. archived_at IS NULL → アーカイブ状態でない → 422
4. archived_at = NULL で UPDATE
5. audit_logs に TEAM_UNARCHIVED（または ORGANIZATION_UNARCHIVED）を記録
6. 204 No Content を返す
```

> - アーカイブ解除後、招待トークンは自動復元しない（アーカイブ時に失効済み）。ADMIN が必要に応じて新規トークンを発行すること
> - アーカイブ解除後、書き込み制限は即時解除される

---

### アーカイブ状態における書き込み制限

アーカイブ済みチーム / 組織（`archived_at IS NOT NULL`）に対する以下の書き込み操作は、Service 層の入り口で `archived_at` を確認し 422（`"TEAM_ARCHIVED"` / `"ORGANIZATION_ARCHIVED"`）を返してブロックする。

**F03 スコープでのブロック対象操作:**

| 操作 | 対象エンドポイント |
|------|-----------------|
| チーム情報更新 | `PATCH /teams/{id}` |
| メンバーロール変更 | `PATCH /teams/{id}/members/{userId}/role` |
| 権限グループ割り当て | `PUT /teams/{id}/members/{userId}/permission-groups` |
| 招待トークン新規発行 | `POST /teams/{id}/invite-tokens` |
| 招待 URL 参加 | `POST /invite/{token}/join`（招待先チーム / 組織がアーカイブ済みの場合）|
| SUPPORTER 自己登録 | `POST /teams/{id}/follow` |
| 組織からのチーム招待送信 | `POST /organizations/{id}/team-invites`（招待先チームがアーカイブ済みの場合）|
| チームへの組織招待承認 | `POST /teams/{id}/org-invites/{id}/accept`（承認するチームがアーカイブ済みの場合）|
| 組織情報更新 | `PATCH /organizations/{id}` |
| 組織メンバーロール変更 | `PATCH /organizations/{id}/members/{userId}/role` |
| 組織権限グループ割り当て | `PUT /organizations/{id}/members/{userId}/permission-groups` |
| 組織招待トークン新規発行 | `POST /organizations/{id}/invite-tokens` |
| 組織 SUPPORTER 自己登録 | `POST /organizations/{id}/follow` |
| 組織権限グループ作成 | `POST /organizations/{id}/permission-groups` |
| 組織権限グループ更新 | `PATCH /organizations/{id}/permission-groups/{groupId}` |

**アーカイブ中も許可する操作（読み取り・クリーンアップ系）:**

| 操作 | 理由 |
|------|------|
| 全 GET 操作 | 読み取り専用のため影響なし |
| `DELETE /teams/{id}` | 論理削除は引き続き可（アーカイブ済みチームも削除可能）|
| `DELETE /teams/{id}/members/{userId}` | ADMIN によるクリーンアップ目的 |
| `DELETE /teams/{id}/me` | メンバー自身の離脱意思は尊重する |
| `DELETE /teams/{id}/follow` | フォロー解除は常に許可 |
| `PATCH /teams/{id}/unarchive` | 解除操作自体はアーカイブ中に呼ばれるべき |
| `DELETE /teams/{id}/invite-tokens/{id}` | 残存トークンの手動失効は許可 |
| `DELETE /organizations/{id}` | 論理削除は引き続き可（アーカイブ済み組織も削除可能）|
| `DELETE /organizations/{id}/members/{userId}` | ADMIN によるクリーンアップ目的 |
| `DELETE /organizations/{id}/me` | メンバー自身の離脱意思は尊重する |
| `DELETE /organizations/{id}/follow` | 組織フォロー解除は常に許可 |
| `PATCH /organizations/{id}/unarchive` | 解除操作自体はアーカイブ中に呼ばれるべき |
| `DELETE /organizations/{id}/invite-tokens/{id}` | 残存トークンの手動失効は許可 |
| ブロック操作（`/teams/{id}/blocks`・`/organizations/{id}/blocks` 系）| ADMIN によるアカウント管理目的 |

> F04（支払い）・F05（スケジュール）等の他フィーチャーの書き込み操作も同様にアーカイブチェックが必要。各フィーチャードキュメントで `archived_at IS NOT NULL` の場合に 422 を返すよう明記すること

---

## 6. セキュリティ考慮事項

- **認可チェック**: 全 Service メソッドの入り口で `team_id` / `organization_id` と `currentUser` の所属を検証する（メンバーでないスコープへのアクセスは 403）
- **親リソース認可チェック（子組織作成時）**: 子組織を作成する際は、作成者が**親組織**に対する ADMIN 権限を持つことを必ず確認する。具体的には:
  - `POST /organizations`（`parent_organization_id` 指定時）: 指定親組織に対して ADMIN 権限が必要
  - 親組織が存在しない・論理削除済みの場合は 404、権限不足は 403 を返す（存在チェックと権限チェックを分けることで情報漏洩を防ぐ）
  - チーム作成（`POST /teams`）は常に独立した状態で作成され、組織への所属は `POST /organizations/{id}/team-invites` 経由で行うため、親リソース認可チェックは不要
- **招待トークン**: UUID v4（推測不可能）を使用。HTTPS 必須。`SELECT ... FOR UPDATE` でアトミックに使用回数チェックと更新を行い同時参加による上限超過を防ぐ
- **ロール昇格制限**: ADMIN は自分と同等以上（priority <= 2）のロールを他ユーザーに付与できない（自己昇格・SYSTEM_ADMIN 付与を防止）
- **ADMIN 昇格時の2FA必須**: ADMIN ロールへの昇格操作は、対象ユーザーが `two_factor_auth` テーブルに有効な TOTP レコードを持つ場合のみ許可する。2FA 未設定のまま ADMIN にすることはできない（README: 「SYSTEM_ADMIN・ADMIN には2FA必須」）
- **組織種別変更**: `org_type` は ADMIN による自己申告制（承認プロセスなし・即時反映）。NONPROFIT / FORPROFIT の識別は UI のカラーコーディング等で視覚的に区別する。変更履歴は audit_logs（`ORGANIZATION_ORG_TYPE_CHANGED`）に before / after を含めて記録し、事後追跡を可能にする
- **スコープ境界**: `user_roles` の `team_id` と `organization_id` を同時に非 NULL にすることをアプリ層で禁止
- **招待URL公開範囲**: `GET /api/v1/invite/{token}` は未認証でアクセス可能だが、チーム名・アイコン・ロール名のみ返す（メンバー一覧・内部情報は含めない）
- **ブロック済みユーザーの招待参加防止**: `POST /invite/{token}/join` では有効性チェック → アーカイブチェック → ブロック済みチェックの順で検証する。`team_blocks` / `organization_blocks` に対象ユーザーのエントリが存在する場合は 403 を返し、招待トークンを保持していてもブロックをバイパスして参加できないようにする
- **フォロー登録の公開制限**: チーム/組織フォローはいずれも `visibility = PUBLIC` かつ `supporter_enabled = TRUE` のエンティティのみ受け付ける。条件を満たさない場合は 403
- **レートリミット**: 以下のエンドポイントに Bucket4j を適用する

  | エンドポイント | 制限 | 単位 | 認証 | 目的 |
  |--------------|------|------|------|------|
  | `GET /invite/{token}` | 10 req/min | **per IP** | 不要 | 未認証エンドポイントへのトークン列挙試行・DoS 防止 |
  | `GET /invite/{token}/qr` | 10 req/min | **per IP** | 不要 | 同上（PNG 画像生成リソースの保護）|
  | `POST /invite/{token}/join` | 10 req/min | per user | 必要 | トークンのブルートフォース探索を防止 |
  | `POST /teams/{id}/invite-tokens` | 10 req/hour | per user | 必要 | 悪意ある ADMIN による大量トークン生成を防止 |
  | `POST /organizations/{id}/invite-tokens` | 10 req/hour | per user | 必要 | 同上 |
  | `POST /teams/{id}/follow` | 10 req/min | per user | 必要 | フォロー操作の乱用防止 |
  | `POST /organizations/{id}/follow` | 10 req/min | per user | 必要 | フォロー操作の乱用防止 |

  > - **per IP vs per user**: 未認証エンドポイント（`GET /invite/*`）は user ID が存在しないため IP アドレスをキーに制限する。認証済みエンドポイントは user ID をキーに適用し、NAT・プロキシ環境での誤検知を防ぐ
  > - **optional-auth エンドポイント**（`GET /teams/{id}`・`GET /organizations/{id}` など認証「任意」のもの）: 連番 ID が列挙可能だが、非公開エンティティは 403/404 のみ返し内部情報を返さない。SNS 経由の大量流入も想定されるため現時点では厳格な制限を設けず、将来的に問題が顕在化した場合に Nginx 等のゲートウェイで IP 単位のグローバル制限を追加することで対応する
  > - トークン作成のレートリミットは per user（ADMIN 個人）で適用する。`max_uses` を大きく設定すれば1枚のトークンで多人数を招待できるため、枚数制限はあくまで大量生成の乱用防止が目的

---

## 7. Flywayマイグレーション

```
V2.001__create_organizations_table.sql
V2.002__create_teams_table.sql
V2.003__add_audit_logs_fk_organization.sql      -- F02 より持ち越し: organizations FK 付与
V2.004__add_audit_logs_fk_team.sql              -- F02 より持ち越し: teams FK 付与
V2.005__create_roles_table.sql
V2.006__create_permissions_table.sql
V2.007__create_role_permissions_table.sql
V2.008__create_user_roles_table.sql
  -- scope_key VARCHAR(30) GENERATED ALWAYS AS (COALESCE(CONCAT('org:', organization_id), CONCAT('team:', team_id), 'platform')) STORED
  -- UNIQUE KEY uq_ur_user_scope (user_id, scope_key)
  -- CONSTRAINT chk_ur_scope CHECK (NOT (team_id IS NOT NULL AND organization_id IS NOT NULL))
  --   ※ 両方 NULL はプラットフォームスコープとして有効のため XOR でなく「同時非 NULL 禁止」制約を使用
V2.009__create_team_permission_groups_table.sql
V2.010__create_team_permission_group_permissions_table.sql
V2.011__create_user_permission_groups_table.sql
V2.012__create_invite_tokens_table.sql
  --   CONSTRAINT chk_it_scope CHECK ((team_id IS NULL) != (organization_id IS NULL))
  --   ※ invite_tokens は必ずチームまたは組織のどちらか一方に属するため真の XOR 制約を使用（両方 NULL 不可）
V2.013__seed_roles.sql
V2.014__seed_permissions.sql
V2.015__seed_role_permissions.sql
V2.016__seed_system_admin_user_role.sql         -- V1.012 で作成済みの SYSTEM_ADMIN ユーザーへ user_roles エントリを追加
V2.017__create_team_blocks_table.sql
V2.018__create_organization_blocks_table.sql
V2.019__add_is_default_to_role_permissions.sql      -- is_default カラム追加・DEPUTY_ADMIN 既存行を FALSE に UPDATE
V2.020__add_target_role_to_team_permission_groups.sql
V2.021__seed_member_permission_ceiling.sql           -- MEMBER 天井3件（is_default=FALSE）を INSERT
V2.022__rename_permission_groups_tables.sql
  -- team_permission_groups → permission_groups にリネーム
  --   organization_id BIGINT UNSIGNED NULL ADD COLUMN
  --   FK: organization_id → organizations (ON DELETE CASCADE)
  --   CONSTRAINT chk_pg_scope CHECK ((team_id IS NULL) != (organization_id IS NULL))
  --   INDEX idx_pg_org_id (organization_id)
  --   INDEX idx_pg_org_role (organization_id, target_role)
  --   INDEX idx_pg_team_id / idx_pg_team_role（旧 idx_tpg_* をリネーム）
  -- team_permission_group_permissions → permission_group_permissions にリネーム
  --   FK および UNIQUE KEY 名を uq_pgp_group_permission / idx_pgp_permission にリネーム
V2.023__refactor_team_org_memberships.sql
  -- teams テーブルから organization_id カラムを DROP:
  --   DROP INDEX idx_team_org（organization_id インデックスを先に削除）
  --   DROP FOREIGN KEY fk_team_organization（FK 制約を先に削除）
  --   DROP COLUMN organization_id
  -- team_org_memberships テーブルを新規作成:
  --   team_id BIGINT UNSIGNED NOT NULL, FK → teams (ON DELETE CASCADE)
  --   organization_id BIGINT UNSIGNED NOT NULL, FK → organizations (ON DELETE CASCADE)
  --   status ENUM('PENDING','ACTIVE') NOT NULL DEFAULT 'PENDING'
  --   invited_by / responded_by BIGINT UNSIGNED NULL, FK → users (SET NULL on delete)
  --   invited_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
  --   responded_at DATETIME NULL
  --   UNIQUE KEY uq_tom_team_org (team_id, organization_id)
  --   INDEX idx_tom_team_id, idx_tom_org_id, idx_tom_status

-- Phase 3 （F04 支払い管理実装時）
V3.007__add_manage_payments_permission.sql
  -- permissions に MANAGE_PAYMENTS（display_name='支払い管理', scope='ORGANIZATION'）を INSERT
  -- role_permissions に3件 INSERT:
  --   (SYSTEM_ADMIN, MANAGE_PAYMENTS, is_default=TRUE)
  --   (ADMIN,        MANAGE_PAYMENTS, is_default=TRUE)
  --   (DEPUTY_ADMIN, MANAGE_PAYMENTS, is_default=FALSE)
  -- ※ MEMBER には追加しない（支払い管理権限は ADMIN/DEPUTY_ADMIN 以上）
```

**マイグレーション上の注意点**
- V2.001（organizations）→ V2.002（teams）の順を守ること（teams は organizations に FK）
- V2.003 は V2.001 直後、V2.004 は V2.002 直後に実行すること（audit_logs の FK 対象テーブルが先に存在すること）
- V2.008（user_roles）は V2.001 / V2.002 / V2.005 がすべて完了していること（FK: users, teams, organizations, roles）
- V2.013〜V2.015 はシードデータ。テスト環境でも必ず投入すること（`roles` テーブルが空だとアプリ起動時にエラー）
- V2.016 は V2.008 の後に実行すること（user_roles テーブル作成後）
- V2.019 は V2.015（シード投入）の後に実行すること。`is_default = FALSE` への UPDATE は DEPUTY_ADMIN の role_id を WHERE 条件に指定する
- V2.021 は V2.006（permissions テーブル）および V2.019（is_default カラム追加）の後に実行すること
- V2.022 は V2.009〜V2.011（権限グループテーブル作成）および V2.001（organizations テーブル作成）の後に実行すること
- V2.023 は V2.002（teams テーブル作成）および V2.001（organizations テーブル作成）の後に実行すること。teams.organization_id の DROP は FK・インデックスを先に削除してから行うこと（MySQL はカラム DROP 前に FK を明示削除する必要がある）

---

## 8. 未解決事項

- [x] 組織レベルの DEPUTY_ADMIN 権限グループの必要性を Phase 2 開始前に確認する（現設計はチームスコープのみ）→ **対応済み（2026-03-08）**: `team_permission_groups` / `team_permission_group_permissions` を `permission_groups` / `permission_group_permissions` に統合リネームし、`organization_id` カラム（XOR 制約）を追加。チーム・組織共通で同一テーブルを使用する設計に変更。API も `GET/POST/PATCH/DELETE /organizations/{id}/permission-groups` および `PUT /organizations/{id}/members/{userId}/permission-groups` を追加
- ~~組織レベルのサポーター登録（`POST /api/v1/organizations/{id}/follow`）の必要性を確認する~~ → 対応済み（2026-02-21、チームと対称的に実装）
- [x] `invite_tokens.created_by` が退会した場合に紐付くトークンを自動失効させるか、そのまま残すかを確定する → **自動失効させず有効のままとする**（`created_by` は `SET NULL on delete`）。理由: 運用継続性の確保・管理責任はチーム/組織に帰属・他の ADMIN による手動 revoke が代替手段として存在するため
- [x] チーム論理削除時に `invite_tokens.revoked_at` を自動設定するか確定する → **自動設定する**（一括失効）。組織論理削除時も同様に直属トークンを一括失効。子チームのトークンは組織削除では失効させない（チームが独立存続するため）。チーム/組織削除フローをビジネスロジックに追加
- [x] `user_roles` の一意性保証方法を Phase 2 実装時に確定する → **STORED 生成列 `scope_key` + `UNIQUE KEY (user_id, scope_key)`** を採用。`COALESCE(CONCAT('org:', organization_id), CONCAT('team:', team_id), 'platform')` で NULL 問題を回避し、DB レベルで一意性を強制。Service 層は例外ハンドリングのみで対応可
- [x] `teams.template` の型: VARCHAR(50) → 将来的に FK → `team_templates` テーブルへ移行するタイミングを確定する → **Phase 2 は VARCHAR(50) のままアプリ層 enum 定数でバリデーション**。テンプレートごとのメタデータ（カスタムフィールド等）が必要になった段階で `team_templates` テーブルを新設し FK へ移行（テンプレート管理 feature doc で設計）
- [x] 組織階層の最大深さ（現在3階層固定）をシステム設定として管理可能にするかを確定する → **`app.org.max-depth` 設定値（デフォルト: 5）に外出し**。再帰構造（`parent_organization_id`）はどの深さにも対応するため、設定値変更だけで上限を調整可能。Service 層・CTE ともにハードコードなしで設定値を参照。超過時は 422
- [x] MEMBER のデフォルト権限（MANAGE_SCHEDULES / MANAGE_FILES / MANAGE_POSTS）をチーム単位または個人単位で剥奪する「制限機能」の設計（Phase 3 以降） → **権限グループによる完全上書き（オーバーライド）方式を採用**。グループ割り当て時は `is_default` を無視しグループ内権限のみが実効権限となるため、マイナス計算のロジックなしで制限が可能。MEMBER 天井を `is_default = FALSE` の3件→ 全6件に拡張。権限解決ロジック・3層制御説明・フロー例を更新
- [x] F04（支払い管理）で定義された `MANAGE_PAYMENTS` パーミッションを `permissions` シードに追加する（Phase 3 実装前に確定）→ **Phase 3 の V3.007 で追加**。SYSTEM_ADMIN ✓ / ADMIN ✓ / DEPUTY_ADMIN △。MEMBER には付与不可（天井エントリなし）。scope = ORGANIZATION（F04 確定設計でチーム・組織の両方に支払い管理 API が存在するため ORGANIZATION に確定）。Flyway マイグレーションと role_permissions シード表に反映済み
- ~~`ORGANIZATION_MEMBER_JOINED` イベントを F02 イベントカタログの「今後追加予定」に追記する~~ → 対応済み（2026-02-21）
- [x] MEMBER / DEPUTY_ADMIN の自主退会フローが未定義 → **対応済み（2026-03-09）**: `DELETE /teams/{id}/me` / `DELETE /organizations/{id}/me` エンドポイントおよびフローを追加。最後のADMIN保護・SUPPORTER 誘導・payment data 保持（F04 参照）・組織退会は直属ロールのみ削除を明記
- [x] **組織の自動アーカイブ条件が未定義** → **対応済み（2026-03-09）**: 案②（直接所属メンバー + ACTIVE 所属チームの全メンバーの最終ログイン12ヶ月超過）を採用。加えて有効な `member_payments` が存在しないことを C3 条件として追加（F04 設計確定後に実装）。カスケードアーカイブは「このorgにのみ ACTIVE 所属するチーム」に限定し、多対多所属チームへの誤波及を防止。子組織はカスケード対象外（独自バッチで判定）。手動アーカイブフローにも同カスケードロジックを適用（reason: MANUAL_CASCADE_ORG）

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-02-21 | 初版作成 |
| 2026-02-21 | SUPPORTER 参加方式を「招待URL」から「フォロー（サポーター登録）API」に修正。チーム・組織それぞれに `supporter_enabled` カラム・`team_blocks`/`organization_blocks` テーブル・フォロー/ブロックAPI・フローを追加。F02 に関連イベント追記 |
| 2026-02-21 | 招待プレビュー・参加レスポンスを `invite_type` + `target` 構造に統一（チーム/組織の両方に対応）。`is_valid = false` 時も `target` を返す仕様を明記 |
| 2026-02-21 | `role_permissions` シードデータ（36件）を追加。DEPUTY_ADMIN の role_permissions が「天井定義」として機能する設計を明記。`DELETE_OTHERS_CONTENT` を天井に含めることで ADMIN が権限グループへ明示的に追加可能とする設計に修正。権限解決ロジックに MEMBER/SUPPORTER/GUEST の扱いを追記 |
| 2026-02-21 | サブグループ（組織階層）対応を追加: `organizations.hierarchy_visibility` カラム追加（NONE/BASIC/FULL）、WITH RECURSIVE CTE によるカスケード通知メカニズム設計、`GET /organizations/{id}/members/all` エンドポイント追加（`scope` クエリパラメータで ORGANIZATION / TEAM / INDIVIDUAL を選択可）、子孫メンバーからの上位組織閲覧制御ロジックをビジネスロジックに追記 |
| 2026-02-21 | カスケード通知に `announcement_scope`（SELF / ORGANIZATIONS / TEAMS）を追加: プッシュ通知宛先（`notification_scope`）と掲示板表示範囲（`announcement_scope`）を独立制御する設計に更新。掲示板伝搬のプルモデル解決ロジック・スコープ×表示先対応表を追記 |
| 2026-02-21 | MEMBER 権限制御（Issue #8）対応: `role_permissions.is_default` カラム追加（TRUE=自動付与 / FALSE=天井のみ）。MEMBER 天井3件追加（MANAGE_ANNOUNCEMENTS / DELETE_OTHERS_CONTENT / SEND_SAFETY_CONFIRMATION）。`team_permission_groups.target_role` カラム追加（DEPUTY_ADMIN / MEMBER）。MEMBER への追加権限付与フロー・3層制御設計を追記。Flyway V2.019〜V2.021 追加 |
| 2026-02-21 | org_type 変更フロー対応（Issue #9）: `org_type_verified` カラムを削除（自己申告制のため審査フラグ不要）。org_type 変更フローをビジネスロジックに追加。セキュリティ考慮事項の org_type 記述を「ADMIN による自己申告制・即時反映・audit_logs に記録」に更新。組織作成フローから SYSTEM_ADMIN への審査通知を削除 |
| 2026-02-21 | 精査・整合性修正: 組織作成フローの `org_type_verified` 残存を削除。ロール変更フローの権限グループ削除条件を「DEPUTY_ADMIN でも MEMBER でもない場合」に修正。メンバー一覧レスポンスの `permission_groups` 返却条件を MEMBER にも拡張。テーブル一覧の `team_permission_groups` 説明を DEPUTY_ADMIN / MEMBER 両対応に更新。ブロックフローのイベント種別を `TEAM/ORGANIZATION_MEMBER_REMOVED`（reason:BLOCK）→ `TEAM/ORGANIZATION_MEMBER_BLOCKED` に修正。チーム・組織のブロック解除フローを新設。未解決事項に `MANAGE_PAYMENTS` パーミッション追加タスクを追記 |
| 2026-02-21 | 招待トークンレートリミット・QRコード対応（Issue #10）: セキュリティ考慮事項のレートリミットをテーブル形式に整理し `POST /teams\|organizations/{id}/invite-tokens` に 10 req/hour per user を追加。`GET /api/v1/invite/{token}/qr` エンドポイントを追加（ZXing による動的 PNG 生成・S3 保存なし・`size` パラメータ対応）|
| 2026-03-08 | 組織階層の最大深さを3階層→`app.org.max-depth` 設定値（デフォルト: 5）に変更: 再帰構造（parent_organization_id）はどの深さにも対応するため、設定値変更だけで上限を調整可能。Service 層・CTE ともにハードコードを廃止し設定値を参照する設計に更新 |
| 2026-03-08 | `teams.template` の移行方針を確定: Phase 2 は VARCHAR(50) + アプリ層 enum 定数バリデーションで運用。`team_templates` テーブルへの FK 移行はメタデータが必要になった段階でテンプレート管理 feature doc にて実施。テーブル定義・制約備考・未解決事項を更新 |
| 2026-03-08 | `user_roles` 一意性保証を確定: STORED 生成列 `scope_key`（COALESCE 式）+ `UNIQUE KEY uq_ur_user_scope (user_id, scope_key)` を採用。テーブル定義・インデックス・制約備考を更新。Flyway V2.008 コメントに生成列定義を明記。未解決事項を解決済みに変更 |
| 2026-03-08 | チーム・組織論理削除フローを追加: 削除時に紐付く invite_tokens を一括失効（revoked_at = NOW()）する設計に確定。invite_tokens 制約・備考に「チーム/組織論理削除時の扱い」を追記。組織削除時は直属トークンのみ失効（子チームのトークンはそのまま）。未解決事項を解決済みに変更 |
| 2026-03-08 | 招待トークン発行者退会時の扱いを確定: 自動失効なし・`created_by` は SET NULL on delete で有効のまま残す設計を `invite_tokens` 制約・備考に明記。理由（運用継続性・管理責任の所在・手動 revoke が代替手段）を記載 |
| 2026-03-08 | 組織レベル権限グループ対応: `team_permission_groups` → `permission_groups`、`team_permission_group_permissions` → `permission_group_permissions` にリネームし、`organization_id` カラム（XOR 制約 `chk_pg_scope`）を追加。チーム・組織スコープを単一テーブルで共通管理する設計に変更。組織向け権限グループ管理 API（`GET/POST/PATCH/DELETE /organizations/{id}/permission-groups` および `PUT /organizations/{id}/members/{userId}/permission-groups`）を追加。権限解決ロジック・3層制御説明をテーブル名変更・スコープ分岐の注記追加に合わせ更新。Flyway V2.022 追加。|
| 2026-03-09 | `MANAGE_PAYMENTS` パーミッションを確定: Phase 3 V3.008 で追加（SYSTEM_ADMIN✓ / ADMIN✓ / DEPUTY_ADMIN△ / MEMBER なし）。permissions シード表に追記・role_permissions シード表に行追加・Phase 3 Flyway マイグレーション定義を追加。scope=TEAM（F04 設計時に再確認予定）|
| 2026-03-10 | `MANAGE_PAYMENTS` の scope を TEAM → ORGANIZATION に確定（F04 で組織レベル支払い管理 API が確定したため）。Flyway マイグレーション名を `V3.xxx` → `V3.007` に採番（F04 でのマイグレーション統合に伴い変更）。未解決事項の scope 変更検討を解決済みに更新 |
| 2026-03-09 | MEMBER 権限をオーバーライドモデルに変更: グループ割り当て時は is_default を無視しグループ内権限のみを実効権限とする設計に統一。権限解決ロジック step 6・MEMBER 天井定義（is_default=FALSE の3件→ 全6件）・3層制御説明・デフォルト権限注記・MEMBER 権限グループ設定フロー（例1:追加維持 / 例2:制限）を更新 |
| 2026-03-09 | 組織階層の最大深さをアプリ層固定から `app.org.max-depth` 設定値（デフォルト: 5）への外出しに修正: 再帰構造を活かした拡張性確保のため。階層構造の説明・例・CTE（`:maxDepth - 1`）・組織作成フロー・organizations テーブル備考・未解決事項を更新 |
| 2026-03-09 | 自分の所属一覧 API を追加: `GET /me/teams` / `GET /me/organizations` エンドポイントを追加。論理削除済みは常に除外、アーカイブ済みは `include_archived` パラメータで制御。ロール・参加日時を含むレスポンス仕様・エラーレスポンスを定義 |
| 2026-03-09 | セキュリティ修正: 招待参加フローにブロック済みチェックを追加（step 5）。team_blocks / organization_blocks にエントリが存在するユーザーは招待トークンを保持していても参加不可（403）。セキュリティ考慮事項に「ブロック済みユーザーの招待参加防止」を追記 |
| 2026-03-09 | チーム-組織の多対多所属設計に変更: `teams.organization_id`（単一FK）を廃止し `team_org_memberships` 中間テーブル（PENDING/ACTIVE ステータス管理・物理削除）を導入。1つのチームが複数の組織に同時所属可能に。チーム作成は常に独立状態で開始し組織への所属は招待フロー経由で行う設計に統一。チーム-組織招待フロー6種（招待送信/承認/拒否/取消/自主離脱/除名）・API エンドポイント10件を追加。カスケード通知 CTE の TEAM/INDIVIDUAL スコープクエリを junction table 経由に更新。`visibility = ORGANIZATION_ONLY` の前提条件を `team_org_memberships` ACTIVE 存在チェックに変更。Flyway V2.023 追加 |
| 2026-03-09 | 親リソース認可チェックを追加: チーム作成（organization_id 指定時）および子組織作成（parent_organization_id 指定時）に、親リソースの ADMIN チェックを必須化。存在しない・論理削除済みは 404、権限不足は 403。DEPUTY_ADMIN への CREATE_TEAM 委任は Phase 3 以降で検討。セキュリティ考慮事項に「親リソース認可チェック」項目を追加 |
| 2026-03-09 | 自主退会フローを追加: `DELETE /teams/{id}/me` / `DELETE /organizations/{id}/me` エンドポイントを追加。最後のADMIN保護（422）・SUPPORTER は `/follow` へ誘導（422）・user_permission_groups の削除・audit_logs TEAM/ORGANIZATION_MEMBER_LEFT（reason: SELF_DEPARTURE）を定義。支払いデータは削除しない（F04 参照）。組織退会は組織直属ロールのみ削除し配下チームへの所属は保持する設計 |
| 2026-03-09 | 精査対応（記載不具合修正）: ① role_permissions シードの `MANAGE_PAYMENTS` 行末注釈をテーブル外に移動（Markdown 崩れ修正）② DEPUTY_ADMIN 3層制御の件数を「Phase 2 時点で11件、Phase 3 以降 12件」に修正 ③ ロール変更フロー step 7 に DEPUTY_ADMIN→MEMBER 遷移時の `target_role='DEPUTY_ADMIN'` グループ削除を明記 ④ 変更履歴の日付順序を時系列順（2026-03-08→2026-03-09）に整列 ⑤ Section 2 MEMBER ロール説明をオーバーライドモデルに合わせ修正 ⑥ `user_roles` 制約備考に CHECK 制約（`chk_ur_scope`）を追加・Flyway V2.008 コメントに反映 |
| 2026-03-09 | `invite_tokens` に DB レベルの XOR CHECK 制約（`chk_it_scope`）を追加: 制約備考の「アプリ層でバリデーション」を `chk_it_scope CHECK ((team_id IS NULL) != (organization_id IS NULL))` に変更。`permission_groups.chk_pg_scope` と同方式。Flyway V2.012 コメントに反映 |
| 2026-03-09 | 一覧取得系 API に visibility 依存の認可ルール・返却粒度を追加（B-7 対応）: ① `GET /teams/{id}/members` に認可ルール表（PUBLIC/ORGANIZATION_ONLY/PRIVATE × アクセス可能者）・ロール別返却フィールド表・エラーレスポンスを追記。ADMIN/DEPUTY_ADMIN は全フィールド、MEMBER/SUPPORTER/GUEST および非メンバー（PUBLIC）は基本プロフィールのみ返却。② `GET /organizations/{id}/members` の仕様セクションを新設（組織は PUBLIC/PRIVATE の2値のみ、ロール別返却粒度はチームと同一）。③ `GET /organizations/{id}/teams` の仕様セクションを新設（PRIVATE 組織は組織メンバーのみ閲覧可・返却チームは各 visibility でフィルタ）。④ `GET /teams/{id}/organizations` の仕様セクションを新設（PRIVATE チームはチームメンバーのみ閲覧可・返却組織は各 visibility でフィルタ）。エンドポイント一覧の認証欄を visibility 依存の注記に更新 |
| 2026-03-09 | アーカイブ・アーカイブ解除 API とフローを追加（B-8 対応）: ① エンドポイント一覧に `PATCH /teams/{id}/archive`・`/unarchive`・`PATCH /organizations/{id}/archive`・`/unarchive` を追加（ADMIN 専用・204 No Content）。② 自動アーカイブバッチ（チームのみ・毎月1日 03:00 JST）をビジネスロジックに追加。判定条件: 全メンバーの最終ログイン（`users.last_login_at`・F02 クロスフィーチャー参照）の最大値が 12ヶ月超過。SUPPORTER 含む全ロールを対象。NULL ログインは `COALESCE(last_login_at, '1970-01-01')` で処理。③ 手動アーカイブ / 解除フローを追加（アーカイブ時に `invite_tokens` を一括失効・audit_logs に reason: MANUAL）。④ アーカイブ状態の書き込み制限一覧（F03 スコープのブロック対象 / 許可対象操作）を追加。F04・F05 等への横断チェック指示を注記。⑤ 組織自動アーカイブ条件が未定義のため未解決事項に追記 |
| 2026-03-09 | 未認証招待 API にレートリミットを追加（C-1・C-2 対応）: `GET /invite/{token}` と `GET /invite/{token}/qr` に 10 req/min per IP を追加。既存テーブルに認証列を追加し per IP / per user の使い分けを明記。optional-auth GET エンドポイント（`/teams/{id}`・`/organizations/{id}` 等）は将来対応（現時点で厳格制限は不採用）とする理由を注記 |
| 2026-03-09 | 招待 API エラーレスポンス・QR キャッシュ対応（C-2 補完）: `GET /invite/{token}` と `GET /invite/{token}/qr` のエラーレスポンス表に `429 Too Many Requests`（レートリミット超過）を追記。`GET /invite/{token}/qr` に ZXing PNG 生成の CPU コストを踏まえた Redis / オンヒープキャッシュ推奨注記（キー: `{token}:{size}`・TTL 5分）を追記 |
| 2026-03-10 | 組織自動アーカイブバッチを追加（未解決事項解決）: 案②（直接所属メンバー + ACTIVE 所属チーム全メンバーの最終ログイン12ヶ月超過）を採用。C3 条件として有効な `member_payments` 不存在を追加（F04 設計確定後に実装・現時点は SQL コメントアウト）。カスケードアーカイブ対象を「このorgにのみ ACTIVE 所属するチーム」に限定（多対多所属チームへの誤波及を防止）。子組織はカスケード対象外。手動アーカイブフローをチーム / 組織で分割し、組織フローにカスケードロジック（MANUAL_CASCADE_ORG）を追加。バッチ SQL・フロー（6〜8ステップ）・audit_logs reason を明記 |
| 2026-03-10 | 精査: F03 設計完了・不整合修正 10件。① 論理削除と ON DELETE CASCADE の混同を3箇所修正（team_org_memberships 制約備考・チーム論理削除フロー step 5 コメント・組織論理削除フローにステップ欠落→step 5 追加）② 組織論理削除フローの注記に user_roles 保持方針を明記 ③ C3 自動アーカイブ条件のカラム名を F04 確定設計に合わせ `expires_at > NOW()` → `valid_until >= CURDATE()` に修正（C3 SQL も `status = 'PAID'` + `valid_until IS NULL` 考慮に更新）④ セキュリティ考慮事項の「POST /teams（organization_id 指定時）」を削除（V2.023 で teams.organization_id は DROP 済み・チーム作成は常に独立）⑤ ステータスを設計完了・最終更新日を 2026-03-10 に更新 ⑥ 変更履歴の V3.008 → V3.007 を反映 |
| 2026-03-10 | 精査②: 不整合修正 4件。① 招待トークン作成フローを追加（role_id バリデーション: ADMIN は priority >= 3 のみ許可、SYSTEM_ADMIN は priority >= 2 まで許可。ADMIN/SYSTEM_ADMIN ロールのトークン作成を防止）② チームブロックフロー・組織ブロックフローに `user_permission_groups` 削除ステップを追加（除名時と同様にグループ割り当てを確実にクリーンアップ）③ ロール変更フロー step 7 に MEMBER → DEPUTY_ADMIN 遷移時の `target_role='MEMBER'` グループ削除を追加（異なる target_role のグループが残存する問題を修正）④ 招待トークン作成フローのビジネスロジックセクションを新設 |
| 2026-03-10 | 精査③: 不整合修正 4件。① `POST /invite/{token}/join` エラーレスポンス表に 403（ブロック済み）・422（アーカイブ済み）を追加 ② 招待参加フローにアーカイブチェックステップ（step 5）を追加（アーカイブ制限一覧との整合性確保）③ エンドポイント一覧の招待トークン管理 6 エンドポイント（POST/GET/DELETE × チーム/組織）の認証欄に DEPUTY_ADMIN 委譲（INVITE_MEMBERS + MANAGE_INVITE_TOKENS 権限）を反映 ④ `GET /organizations/{id}/teams` の ORGANIZATION_ONLY 返却条件コメントを修正（PUBLIC 組織では非メンバーもアクセス可能な点を考慮） |
| 2026-03-10 | 精査④: 不整合修正 3件。① アーカイブ書き込み制限テーブルに組織スコープ操作を追加（ブロック対象: `PATCH /organizations/{id}` 等 7 操作、許可対象: `DELETE /organizations/{id}` 等 6 操作）② セキュリティ考慮事項のブロック済みチェック説明を「有効性チェックの直後に」→「有効性チェック → アーカイブチェック → ブロック済みチェックの順で検証」に修正（精査③で挿入したアーカイブチェックとの整合性確保）③ ブロックエンドポイント説明の「SUPPORTER ロールも同時除名」→「ロールも同時除名」に修正（実装フローは全ロールを `user_roles` から削除するため） |
