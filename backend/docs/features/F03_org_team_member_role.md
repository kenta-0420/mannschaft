# F03: 組織・チーム・メンバー・ロール管理

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 2
> **最終更新**: 2026-03-08

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
| MEMBER | デフォルトで MANAGE_SCHEDULES / MANAGE_FILES / MANAGE_POSTS を保持。ADMIN が権限グループを割り当てることで MANAGE_ANNOUNCEMENTS 等を追加付与可能 |
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
- `parent_organization_id` の循環参照はアプリケーション層で防ぐ（最大3階層）
- `hierarchy_visibility` はこの組織を「子から上向きに見たとき」の可視範囲を制御する。`visibility`（外部からの検索・閲覧）とは独立して設定できる

---

#### `teams`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `organization_id` | BIGINT UNSIGNED | YES | NULL | 親組織（FK → organizations; NULL = 独立チーム）|
| `name` | VARCHAR(100) | NO | — | チーム/店舗/教室 正式名称 |
| `name_kana` | VARCHAR(100) | YES | NULL | フリガナ |
| `nickname1` | VARCHAR(50) | YES | NULL | 愛称1 |
| `nickname2` | VARCHAR(50) | YES | NULL | 愛称2 |
| `template` | VARCHAR(50) | YES | NULL | テンプレート種別（例: SPORTS, CLINIC, SCHOOL; 将来 FK → team_templates）|
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
INDEX idx_team_org (organization_id)
INDEX idx_team_archived (archived_at)
INDEX idx_team_pref_city (prefecture, city)   -- 地域検索用
INDEX idx_team_name (name)
```

**制約・備考**
- 論理削除: `deleted_at DATETIME nullable`
- `visibility = 'ORGANIZATION_ONLY'` は `organization_id IS NOT NULL` の場合のみ有効（独立チームへの設定はアプリ層でバリデーション）
- アーカイブトリガー: 全メンバーの最終ログインのうち最新が12ヶ月経過（README §アーカイブ規約参照）

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

> 各機能モジュール（スケジュール・ファイル・チャット等）の実装時に順次追加する。

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

**シードデータ（V2.015__seed_role_permissions.sql / V2.021__seed_member_permission_ceiling.sql）**

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

合計レコード数: 11 + 11 + 11 + 6（✓3 + △3） = **39件**

**制約・備考**
- **SYSTEM_ADMIN**: 全11件（is_default = TRUE）。権限チェックは JWT 判定に統一（runtime で DB 参照しない）。シードは監査・将来対応のため投入する
- **ADMIN**: 全11件（is_default = TRUE）。`DELETE_OTHERS_CONTENT` を含む全パーミッションを行使可能
- **DEPUTY_ADMIN**: 全11件（is_default = FALSE）。**天井（ceiling）定義**として機能する。runtime での権限解決は role_permissions を参照せず `user_permission_groups` のみを使用する（権限グループ未割り当ての DEPUTY_ADMIN は実効パーミッション 0）
- **MEMBER（is_default = TRUE）**: `MANAGE_SCHEDULES` / `MANAGE_FILES` / `MANAGE_POSTS` の3件。チーム参加と同時に全 MEMBER へ自動付与
- **MEMBER（is_default = FALSE）**: `DELETE_OTHERS_CONTENT` / `MANAGE_ANNOUNCEMENTS` / `SEND_SAFETY_CONFIRMATION` の3件。天井のみ（自動付与なし）。ADMIN が MEMBER 用権限グループを作成し特定ユーザーへ割り当てた場合のみ有効
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
| `granted_by` | BIGINT UNSIGNED | YES | NULL | FK → users（付与者; システム付与・シードは NULL; SET NULL on delete）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
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
- 1ユーザーが同一スコープに複数ロールを持つことはできない（アプリケーション Service 層で強制）
- MySQL UNIQUE の NULL 制限（NULL 同士が一致しない）により DB 制約では一意性を完全保証できないため、Service 層で挿入前に重複確認クエリを実行する
- `team_id` と `organization_id` を同時に非 NULL にすることはアプリ層で禁止（XOR または両方 NULL）
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
- `target_role = 'MEMBER'` のグループに追加できるパーミッションは `role_permissions WHERE role_id = MEMBER AND is_default = FALSE` の範囲内（MANAGE_ANNOUNCEMENTS / DELETE_OTHERS_CONTENT / SEND_SAFETY_CONFIRMATION）

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
- `team_id` と `organization_id` はどちらか一方のみ非 NULL（XOR; アプリ層でバリデーション）
- `used_count` インクリメントは `SELECT ... FOR UPDATE` でアトミックに実行（同時参加による上限超過防止）
- 有効期限の選択肢: 1日 / 7日 / 30日 / 90日 / 無期限

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

### ER図（テキスト形式）
```
organizations (1) ──── (N) organizations        ※ parent_organization_id（自己参照）
organizations (1) ──── (N) teams                ※ organization_id（独立チームは NULL）
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
| GET | `/api/v1/organizations/{id}/members` | 必要 | 組織メンバー一覧（直接所属）|
| PATCH | `/api/v1/organizations/{id}/members/{userId}/role` | 必要（ADMIN）| 組織メンバーロール変更 |
| DELETE | `/api/v1/organizations/{id}/members/{userId}` | 必要（ADMIN）| 組織メンバー除名 |
| POST | `/api/v1/organizations/{id}/invite-tokens` | 必要（ADMIN）| 組織招待トークン発行 |
| GET | `/api/v1/organizations/{id}/invite-tokens` | 必要（ADMIN）| 組織招待トークン一覧 |
| DELETE | `/api/v1/organizations/{id}/invite-tokens/{tokenId}` | 必要（ADMIN）| 組織招待トークン失効 |
| POST | `/api/v1/teams` | 必要 | チーム作成 |
| GET | `/api/v1/teams/{id}` | 任意 | チーム詳細取得（可視性による）|
| PATCH | `/api/v1/teams/{id}` | 必要（ADMIN+）| チーム情報更新 |
| DELETE | `/api/v1/teams/{id}` | 必要（ADMIN+）| チーム論理削除 |
| GET | `/api/v1/teams/{id}/members` | 必要 | チームメンバー一覧 |
| PATCH | `/api/v1/teams/{id}/members/{userId}/role` | 必要（ADMIN）| メンバーロール変更 |
| DELETE | `/api/v1/teams/{id}/members/{userId}` | 必要（ADMIN）| メンバー除名 |
| POST | `/api/v1/teams/{id}/invite-tokens` | 必要（ADMIN）| チーム招待トークン発行 |
| GET | `/api/v1/teams/{id}/invite-tokens` | 必要（ADMIN）| チーム招待トークン一覧 |
| DELETE | `/api/v1/teams/{id}/invite-tokens/{tokenId}` | 必要（ADMIN）| 招待トークン失効 |
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
| POST | `/api/v1/teams/{id}/follow` | 必要 | チームをフォロー（SUPPORTER 自己登録・招待コード不要）|
| DELETE | `/api/v1/teams/{id}/follow` | 必要 | フォロー解除（自分の SUPPORTER ロールを削除）|
| GET | `/api/v1/teams/{id}/blocks` | 必要（ADMIN）| ブロック一覧 |
| POST | `/api/v1/teams/{id}/blocks` | 必要（ADMIN/DEPUTY_ADMIN）| ユーザーをブロック（自己登録禁止・現在の SUPPORTER ロールも同時除名）|
| DELETE | `/api/v1/teams/{id}/blocks/{userId}` | 必要（ADMIN/DEPUTY_ADMIN）| ブロック解除 |
| POST | `/api/v1/organizations/{id}/follow` | 必要 | 組織をフォロー（SUPPORTER 自己登録・招待コード不要）|
| DELETE | `/api/v1/organizations/{id}/follow` | 必要 | 組織フォロー解除 |
| GET | `/api/v1/organizations/{id}/blocks` | 必要（ADMIN）| 組織ブロック一覧 |
| POST | `/api/v1/organizations/{id}/blocks` | 必要（ADMIN/DEPUTY_ADMIN）| 組織ユーザーをブロック |
| DELETE | `/api/v1/organizations/{id}/blocks/{userId}` | 必要（ADMIN/DEPUTY_ADMIN）| 組織ブロック解除 |
| GET | `/api/v1/organizations/{id}/members/all` | 必要（ADMIN+）| 組織サブツリーの全メンバー一覧（WITH RECURSIVE で全子組織・子チームを網羅・カスケード通知対象確認用）|
| GET | `/api/v1/invite/{token}/qr` | 不要 | 招待QRコード画像取得（PNG）|

### リクエスト／レスポンス仕様

#### `POST /api/v1/teams`

**リクエストボディ**
```json
{
  "name": "FCマンシャフト",
  "nickname1": "マンシャフト",
  "nickname2": null,
  "organization_id": null,
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

**クエリパラメータ**
| パラメータ | 型 | 説明 |
|-----------|---|------|
| `page` | Int | ページ番号（0始まり）|
| `size` | Int | 1ページ件数（最大100）|

**レスポンス（200 OK）**

※ `created_at`（参加日時）昇順で返す。

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
    "total_pages": 1
  }
}
```

> `permission_groups` は `role = DEPUTY_ADMIN` または `role = MEMBER`（権限グループが1件以上割り当て済みの場合）のユーザーに返す。その他のロールまたは未割り当て MEMBER は空配列。

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

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 404 | トークンが存在しない |
| 400 | `size` パラメータが範囲外 |

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
| 409 | すでにメンバーとして参加済み |

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

## 5. ビジネスロジック

### チーム作成フロー

```
1. POST /api/v1/teams を受付
2. リクエストボディをバリデーション
3. organization_id が指定された場合: 対象組織が存在・未削除か確認
4. teams に INSERT
5. user_roles に (user_id=作成者, role_id=ADMIN, team_id=新チームID) を INSERT
6. audit_logs に TEAM_CREATED を記録（team_id = 新チームID）
7. 201 Created を返す
```

### 組織作成フロー

```
1. POST /api/v1/organizations を受付
2. parent_organization_id が指定された場合: 循環参照・深さ（3階層）チェック
3. organizations に INSERT
4. user_roles に (user_id=作成者, role_id=ADMIN, organization_id=新組織ID) を INSERT
5. audit_logs に ORGANIZATION_CREATED を記録（metadata: {"org_type": "NONPROFIT" or "FORPROFIT"}）
6. 201 Created を返す
```

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

### 招待参加フロー

```
1. GET /api/v1/invite/{token} でプレビュー（オプション）
2. POST /api/v1/invite/{token}/join を受付
3. invite_tokens を SELECT ... FOR UPDATE で取得（排他ロック）
4. 有効性チェック: revoked_at IS NULL かつ
   (expires_at IS NULL OR expires_at > NOW()) かつ
   (max_uses IS NULL OR used_count < max_uses)
   → いずれか失敗で 400
5. user_roles に当該スコープのエントリが既存か確認 → 存在すれば 409
6. user_roles に INSERT（role_id = invite_tokens.role_id）
7. invite_tokens.used_count を +1 UPDATE
8. audit_logs に TEAM_MEMBER_JOINED または ORGANIZATION_MEMBER_JOINED を記録
9. 200 OK を返す
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
7. 変更後ロールが DEPUTY_ADMIN でも MEMBER でもない場合: user_permission_groups を全削除（MEMBER への変更は権限グループをそのまま保持）
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
   a. role_permissions WHERE role_id = MEMBER AND is_default = TRUE（基本3件）をベースとして取得
   b. user_permission_groups（当該ユーザー・スコープ）
        → permission_groups WHERE target_role = 'MEMBER'（AND team_id/organization_id でスコープ絞り込み）
        → permission_group_permissions → permissions
      を結合して追加パーミッションを取得（権限グループ未割り当ての場合は追加なし）
   c. a UNION b = 実効パーミッション
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
6. team_blocks に INSERT（既にブロック済みの場合は 409）
7. audit_logs に TEAM_MEMBER_BLOCKED を記録（target_user_id = 対象ユーザー）
8. 204 No Content を返す
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
5. organization_blocks に INSERT（既にブロック済みの場合は 409）
6. audit_logs に ORGANIZATION_MEMBER_BLOCKED を記録（target_user_id = 対象ユーザー）
7. 204 No Content を返す
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

### DEPUTY_ADMIN / MEMBER 権限の 3 層制御

README 記載の 3 層制御は DEPUTY_ADMIN と MEMBER の両ロールに適用される。チームスコープ・組織スコープともに同一の仕組みを使用する（`permission_groups.team_id` または `permission_groups.organization_id` でスコープを区別）。

**DEPUTY_ADMIN の 3 層制御**

1. **SYSTEM_ADMIN が天井を設定**: `role_permissions WHERE role_id = DEPUTY_ADMIN AND is_default = FALSE`（全11件）が付与可能な上限。ADMIN は天井に含まれるパーミッションのみを権限グループに追加できる
2. **ADMIN が権限グループを構成**: `permission_groups（target_role = 'DEPUTY_ADMIN', team_id/organization_id = スコープID）` + `permission_group_permissions` で天井内のパーミッションを選択して名前付きグループを作成
3. **ADMIN がユーザーへ割り当て**: `user_permission_groups` で DEPUTY_ADMIN ユーザーと権限グループを紐付け（未割り当て = 実効パーミッション 0）

**MEMBER の 3 層制御**

1. **SYSTEM_ADMIN が天井を設定**: `role_permissions WHERE role_id = MEMBER AND is_default = FALSE`（MANAGE_ANNOUNCEMENTS / DELETE_OTHERS_CONTENT / SEND_SAFETY_CONFIRMATION の3件）が追加付与可能な上限
2. **ADMIN が権限グループを構成**: `permission_groups（target_role = 'MEMBER', team_id/organization_id = スコープID）` + `permission_group_permissions` で天井内のパーミッションを選択してグループを作成
3. **ADMIN がユーザーへ割り当て**: `user_permission_groups` で対象 MEMBER と権限グループを紐付け（未割り当て = デフォルトの3件のみ）

> **MEMBER のデフォルト権限（is_default = TRUE の3件）は常に有効**であり、権限グループの割り当て有無にかかわらず全 MEMBER が保持する。権限グループは「追加付与」のみ行い、デフォルト権限の剥奪は行わない（デフォルト権限の個別制限は Phase 3 以降で設計）。
>
> **`DELETE_OTHERS_CONTENT` の扱い**: DEPUTY_ADMIN / MEMBER 双方の天井に含める。いかなるデフォルト権限グループにも含めない。ADMIN が意図的に付与した場合のみ有効。

### MEMBER への追加権限付与フロー

```
1. ADMIN が MEMBER 用権限グループを作成
   POST /api/v1/teams/{id}/permission-groups  body: { "target_role": "MEMBER", "name": "お知らせ編集担当" }
2. 権限グループに MEMBER 天井内のパーミッションを設定
   PATCH /api/v1/teams/{id}/permission-groups/{groupId}  body: { "permission_ids": [MANAGE_ANNOUNCEMENTS の id] }
3. 対象 MEMBER に権限グループを割り当て
   PUT /api/v1/teams/{id}/members/{userId}/permission-groups  body: { "group_ids": [groupId] }
4. 権限解決ロジックが以後 a UNION b（基本3件 + グループのパーミッション）を返す
5. audit_logs に TEAM_MEMBER_PERMISSION_GROUP_ASSIGNED を記録
```

---

### 組織階層とカスケード通知

#### 階層構造

`organizations.parent_organization_id`（自己参照 FK）により最大3階層の組織ツリーを構成する。

```
例: 学校（組織 / トップレベル）
      └── 1年生（組織 / 第2層）
            └── 1年A組（チーム）── 個人メンバー
            └── 1年B組（チーム）── 個人メンバー
      └── 2年生（組織 / 第2層）
            └── 2年A組（チーム）── 個人メンバー
```

- **循環参照防止**: 親組織設定時にアプリ層で祖先を遡り、自組織が含まれないことを確認する
- **最大3階層**: depth 0（トップ）/ depth 1（中間）/ depth 2（末端）。チームは depth に関係なく任意の組織に所属可

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

  -- 再帰ケース: 子組織を追加（最大3階層 = depth 0〜2）
  SELECT o.id, ot.depth + 1
  FROM organizations o
  INNER JOIN org_subtree ot ON o.parent_organization_id = ot.id
  WHERE o.deleted_at IS NULL AND ot.depth < 2
)

-- scope = ORGANIZATION: 組織直属メンバーのみ（チームメンバーは含めない）
SELECT DISTINCT ur.user_id FROM user_roles ur
WHERE ur.organization_id IN (SELECT id FROM org_subtree);

-- scope = TEAM: 子チームメンバーのみ（組織直属は含めない）
SELECT DISTINCT ur.user_id FROM user_roles ur
INNER JOIN teams t ON ur.team_id = t.id
WHERE t.organization_id IN (SELECT id FROM org_subtree)
  AND t.deleted_at IS NULL AND t.archived_at IS NULL;

-- scope = INDIVIDUAL: 上記2クエリの UNION（全員）
SELECT DISTINCT ur.user_id FROM user_roles ur
WHERE ur.organization_id IN (SELECT id FROM org_subtree)
UNION
SELECT DISTINCT ur.user_id FROM user_roles ur
INNER JOIN teams t ON ur.team_id = t.id
WHERE t.organization_id IN (SELECT id FROM org_subtree)
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

## 6. セキュリティ考慮事項

- **認可チェック**: 全 Service メソッドの入り口で `team_id` / `organization_id` と `currentUser` の所属を検証する（メンバーでないスコープへのアクセスは 403）
- **招待トークン**: UUID v4（推測不可能）を使用。HTTPS 必須。`SELECT ... FOR UPDATE` でアトミックに使用回数チェックと更新を行い同時参加による上限超過を防ぐ
- **ロール昇格制限**: ADMIN は自分と同等以上（priority <= 2）のロールを他ユーザーに付与できない（自己昇格・SYSTEM_ADMIN 付与を防止）
- **ADMIN 昇格時の2FA必須**: ADMIN ロールへの昇格操作は、対象ユーザーが `two_factor_auth` テーブルに有効な TOTP レコードを持つ場合のみ許可する。2FA 未設定のまま ADMIN にすることはできない（README: 「SYSTEM_ADMIN・ADMIN には2FA必須」）
- **組織種別変更**: `org_type` は ADMIN による自己申告制（承認プロセスなし・即時反映）。NONPROFIT / FORPROFIT の識別は UI のカラーコーディング等で視覚的に区別する。変更履歴は audit_logs（`ORGANIZATION_ORG_TYPE_CHANGED`）に before / after を含めて記録し、事後追跡を可能にする
- **スコープ境界**: `user_roles` の `team_id` と `organization_id` を同時に非 NULL にすることをアプリ層で禁止
- **招待URL公開範囲**: `GET /api/v1/invite/{token}` は未認証でアクセス可能だが、チーム名・アイコン・ロール名のみ返す（メンバー一覧・内部情報は含めない）
- **フォロー登録の公開制限**: チーム/組織フォローはいずれも `visibility = PUBLIC` かつ `supporter_enabled = TRUE` のエンティティのみ受け付ける。条件を満たさない場合は 403
- **レートリミット**: 以下のエンドポイントに Bucket4j を適用する

  | エンドポイント | 制限 | 単位 | 目的 |
  |--------------|------|------|------|
  | `POST /teams/{id}/invite-tokens` | 10 req/hour | per user | 悪意ある ADMIN による大量トークン生成を防止 |
  | `POST /organizations/{id}/invite-tokens` | 10 req/hour | per user | 同上 |
  | `POST /invite/{token}/join` | 10 req/min | per user | トークンのブルートフォース探索を防止 |
  | `POST /teams/{id}/follow` | 10 req/min | per user | フォロー操作の乱用防止 |
  | `POST /organizations/{id}/follow` | 10 req/min | per user | フォロー操作の乱用防止 |

  > トークン作成のレートリミットは per user（ADMIN 個人）で適用する。`max_uses` を大きく設定すれば1枚のトークンで多人数を招待できるため、枚数制限はあくまで大量生成の乱用防止が目的

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
V2.009__create_team_permission_groups_table.sql
V2.010__create_team_permission_group_permissions_table.sql
V2.011__create_user_permission_groups_table.sql
V2.012__create_invite_tokens_table.sql
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

---

## 8. 未解決事項

- [x] 組織レベルの DEPUTY_ADMIN 権限グループの必要性を Phase 2 開始前に確認する（現設計はチームスコープのみ）→ **対応済み（2026-03-08）**: `team_permission_groups` / `team_permission_group_permissions` を `permission_groups` / `permission_group_permissions` に統合リネームし、`organization_id` カラム（XOR 制約）を追加。チーム・組織共通で同一テーブルを使用する設計に変更。API も `GET/POST/PATCH/DELETE /organizations/{id}/permission-groups` および `PUT /organizations/{id}/members/{userId}/permission-groups` を追加
- ~~組織レベルのサポーター登録（`POST /api/v1/organizations/{id}/follow`）の必要性を確認する~~ → 対応済み（2026-02-21、チームと対称的に実装）
- [ ] `invite_tokens.created_by` が退会した場合に紐付くトークンを自動失効させるか、そのまま残すかを確定する
- [ ] チーム論理削除時に `invite_tokens.revoked_at` を自動設定するか確定する
- [ ] `user_roles` の一意性保証方法を Phase 2 実装時に確定する（MySQL 8.0 関数インデックス / アプリ層 + SELECT FOR UPDATE / generated column 等）
- [ ] `teams.template` の型: VARCHAR(50) → 将来的に FK → `team_templates` テーブルへ移行するタイミングを確定する（テンプレート管理 feature doc で設計）
- [ ] 組織階層の最大深さ（現在3階層固定）をシステム設定として管理可能にするかを確定する
- [ ] MEMBER のデフォルト権限（MANAGE_SCHEDULES / MANAGE_FILES / MANAGE_POSTS）をチーム単位または個人単位で剥奪する「制限機能」の設計（Phase 3 以降）。現設計では追加付与のみ対応
- [ ] F04（支払い管理）で定義された `MANAGE_PAYMENTS` パーミッションを `permissions` シードに追加する（Phase 3 実装前に確定）
- ~~`ORGANIZATION_MEMBER_JOINED` イベントを F02 イベントカタログの「今後追加予定」に追記する~~ → 対応済み（2026-02-21）

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
| 2026-03-08 | 組織レベル権限グループ対応: `team_permission_groups` → `permission_groups`、`team_permission_group_permissions` → `permission_group_permissions` にリネームし、`organization_id` カラム（XOR 制約 `chk_pg_scope`）を追加。チーム・組織スコープを単一テーブルで共通管理する設計に変更。組織向け権限グループ管理 API（`GET/POST/PATCH/DELETE /organizations/{id}/permission-groups` および `PUT /organizations/{id}/members/{userId}/permission-groups`）を追加。権限解決ロジック・3層制御説明をテーブル名変更・スコープ分岐の注記追加に合わせ更新。Flyway V2.022 追加。|
