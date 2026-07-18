## 3. DB設計

### テーブル一覧
| テーブル名 | 役割 | 論理削除 |
|-----------|------|---------|
| `organizations` | 組織マスター | あり |
| `teams` | チームマスター | あり |
| `roles` | ロールマスター（固定6種・シード）| なし |
| `permissions` | パーミッションマスター（シード）| なし |
| `role_permissions` | ロール↔パーミッション紐付け（シード）| なし |
| `user_roles` | ユーザーの**権限ロール**割り当て（スコープ付き）。F00.5 Phase 4 以降は ADMIN/DEPUTY_ADMIN/GUEST 等の権限ロール専用に縮退。MEMBER/SUPPORTER の所属管理は `memberships` テーブルに移管済み | なし |
| `memberships` | ユーザーの**所属メンバーシップ**（MEMBER/SUPPORTER）。F00.5 で新設。`scope_type`/`scope_id` で組織・チーム両対応。`left_at IS NULL` = アクティブメンバー | あり（`left_at` セットで退会表現）|
| `permission_groups` | DEPUTY_ADMIN / MEMBER 用権限グループ（チーム・組織共通; ADMIN が定義）| あり |
| `permission_group_permissions` | 権限グループ↔パーミッション紐付け | なし |
| `user_permission_groups` | ユーザー↔権限グループ割り当て | なし |
| `invite_tokens` | 招待URL/QRコード用トークン | なし（revoked_at で失効管理）|
| `ownership_transfer_offers` | オーナー委譲の承諾型オファー（打診→承諾で ADMIN 委譲を実行）。2026-07-18 承諾型化で新設 | なし（status で状態管理）|
| `team_org_memberships` | チーム↔組織の多対多所属関係（組織からの招待・チームの承認で成立）| なし（物理削除。履歴は audit_logs で管理）|
| `team_blocks` | チームのサポーター自己登録ブロックリスト（ADMIN/DEPUTY_ADMIN が管理）| なし |
| `organization_blocks` | 組織のサポーター自己登録ブロックリスト（ADMIN/DEPUTY_ADMIN が管理）| なし |
| `organization_officers` | 組織の役員一覧（氏名・役職・並び順・個別表示可否）| なし（物理削除）|
| `team_officers` | チームの役員一覧（氏名・役職・並び順・個別表示可否）| なし（物理削除）|
| `organization_custom_fields` | 組織のフリー記述項目（ラベル＋値の任意追加項目）| なし（物理削除）|
| `team_custom_fields` | チームのフリー記述項目（ラベル＋値の任意追加項目）| なし（物理削除）|

### テーブル定義

#### `organizations`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `slug` | VARCHAR(30) | NO | — | URLに使用する一意なスラッグ（英小文字・数字・ハイフン、3〜30文字）|
| `name` | VARCHAR(100) | NO | — | 組織正式名称 |
| `name_kana` | VARCHAR(100) | YES | NULL | フリガナ（地域検索用）|
| `nickname1` | VARCHAR(50) | YES | NULL | 愛称1 |
| `nickname2` | VARCHAR(50) | YES | NULL | 愛称2 |
| `org_type` | ENUM('NONPROFIT', 'FORPROFIT') | NO | 'FORPROFIT' | 組織種別（自己申告制・課金ロジックに影響）|
| `parent_organization_id` | BIGINT UNSIGNED | YES | NULL | 親組織（FK → organizations; NULL = トップレベル）|
| `prefecture` | VARCHAR(10) | YES | NULL | 都道府県 |
| `city` | VARCHAR(50) | YES | NULL | 市区町村 |
| `description` | TEXT | YES | NULL | 組織説明（最大2000文字はアプリ層でバリデーション）|
| `icon_url` | VARCHAR(512) | YES | NULL | アイコン画像R2キー（**実装: F01.6**）|
| `banner_url` | VARCHAR(512) | YES | NULL | バナー画像R2キー（**実装: F01.6**）|
| `homepage_url` | VARCHAR(512) | YES | NULL | 組織ホームページURL（`http://` または `https://` のみ許可。アプリ層でスキームバリデーション）|
| `established_date` | DATE | YES | NULL | 設立年月日。日不明時は `established_date_precision` を `YEAR` または `YEAR_MONTH` に設定し、月・日を `01` で埋める（例: `2015-01-01`）|
| `established_date_precision` | ENUM('YEAR', 'YEAR_MONTH', 'FULL') | YES | NULL | 設立日の精度。`established_date` が NULL のときは本カラムも NULL。UI表示時はこの精度に従って `YYYY年` / `YYYY年M月` / `YYYY年M月D日` を切り替える |
| `philosophy` | TEXT | YES | NULL | 組織理念・フィロソフィー（最大2000文字はアプリ層でバリデーション）|
| `profile_visibility` | JSON | YES | NULL | プロフィール項目ごとの公開可否フラグ（後述）。NULL は「全項目デフォルト＝非公開」扱い |
| `visibility` | ENUM('PUBLIC', 'PRIVATE') | NO | 'PRIVATE' | 情報公開レベル（外部公開制御）|
| `hierarchy_visibility` | ENUM('NONE', 'BASIC', 'FULL') | NO | 'NONE' | 子組織・チームのメンバーに対するこの組織の閲覧範囲。NONE=非公開 / BASIC=組織名・説明・アイコンのみ / FULL=visibility 設定範囲内の全コンテンツ |
| `supporter_enabled` | BOOLEAN | NO | FALSE | サポーター（フォロー）登録機能の有効化フラグ。TRUE かつ visibility=PUBLIC の場合のみ招待コード不要でフォロー可能 |
| `supporter_name_disclosure` | ENUM('DISPLAY_NAME','REAL_NAME') | NO | 'DISPLAY_NAME' | サポーター向け氏名表示モード（**実装: F19.1**）。詳細: `F19.1_public_pages_identity_disclosure.md` §5.1.2 |
| `map_embed_url` | VARCHAR(2048) | YES | NULL | Google Maps 埋め込み URL（**実装: F19.1**） |
| `archived_at` | DATETIME | YES | NULL | アーカイブ日時（NULL = アクティブ）|
| `deleted_at` | DATETIME | YES | NULL | 論理削除日時 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_organizations_slug (slug)
INDEX idx_org_parent (parent_organization_id)
INDEX idx_org_archived (archived_at)    -- アーカイブバッチ用
INDEX idx_org_name (name)               -- 検索用
```

**制約・備考**
- 論理削除: `deleted_at DATETIME nullable`
- `slug`: 英小文字・数字・ハイフンのみ（パターン: `^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$`・3〜30文字）。アプリ層で正規表現バリデーション。**正準仕様（村方式）**: 組織作成時に**ユーザーが任意の slug を入力する**（村 `F17.1` の `VillageCreateRequest.slug` と同じ UX）。グローバル一意・予約語禁止。`name` からの自動生成は **「編集可能な提案プレフィル」に降格**する（FE が作成フォームで `name` から ASCII 変換した候補を初期表示し、ユーザーがそのまま採用・編集できる）。**強制的な連番付与（`org-000017` のような数値サフィックス）は廃止方針**。重複時は 400（後述 `slug-check` API でリアルタイム確認）を返し、ユーザーに別の slug 入力を促す。予約語・正準仕様の詳細は §6（`04_security_operations.md`）の「slug 正準仕様」節を参照
- `parent_organization_id` の循環参照はアプリケーション層で防ぐ。最大深さは `app.org.max-depth`（デフォルト: 5）で管理し、Service 層がこの設定値を参照して depth を検証する
- `hierarchy_visibility` はこの組織を「子から上向きに見たとき」の可視範囲を制御する。`visibility`（外部からの検索・閲覧）とは独立して設定できる
- `homepage_url` は `^https?://` にマッチする場合のみ許可。`javascript:`, `data:`, `file:` 等のスキームは拒否（XSS/フィッシング対策）
- `established_date` + `established_date_precision` は常にペアで扱う。`established_date IS NOT NULL` ⇒ `established_date_precision IS NOT NULL`（アプリ層バリデーション）
- `profile_visibility` JSON の構造:
  ```json
  {
    "homepage_url": false,
    "established_date": false,
    "philosophy": false,
    "officers": false,
    "custom_fields": false
  }
  ```
  - 既知のキー: `homepage_url` / `established_date` / `philosophy` / `officers` / `custom_fields`
  - 各キーは BOOLEAN。未指定キーは `false` 扱い（プライバシー既定）
  - 新規作成時のデフォルト: `NULL`（= 全項目非公開）。`UPDATE` 時に部分キー指定があれば、指定されなかったキーは従来値を維持（PATCH セマンティクスのマージ動作。JSON 全体の置き換えではない）
  - 将来の拡張項目を追加する場合は、キーを追加してこの設計書と README の変更履歴に明記する
  - 組織の `visibility = 'PRIVATE'` のときは `profile_visibility` の設定に関わらず全項目非公開（API 応答から除外）
  - JSON バリデーション: 上記5キー以外は許容しない（strict mode。不明キーは 400 エラー）
- **設計ノート: JSON vs 個別 BOOLEAN カラム**
  - JSON を採用した理由: ① 将来新しい公開可否フラグを追加する際にスキーマ変更不要 ② 5〜10項目程度であれば集計クエリが不要なため JSON パスの検索コストは無視できる ③ JPA の `@Convert` + 専用 DTO で型安全性は担保可能
  - トレードオフ: ① インデックスを張りづらい（JSON パスインデックスは可能だが複雑）② 不正値検出はアプリ層で実施する必要あり → `JsonNode` ではなく strict DTO (`ProfileVisibility` record with Boolean fields) で deserialize し、未知キーは Jackson の `FAIL_ON_UNKNOWN_PROPERTIES=true` で弾く
  - 将来 BOOLEAN カラムへ移行する判断基準: 公開可否フラグの「集計クエリ」（例:「`homepage_url` を公開している組織数」）が運用上必要になった場合、個別 BOOLEAN カラムに移行する

---

#### `teams`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `slug` | VARCHAR(30) | NO | — | URLに使用する一意なスラッグ（英小文字・数字・ハイフン、3〜30文字）|
| `name` | VARCHAR(100) | NO | — | チーム/店舗/教室 正式名称 |
| `name_kana` | VARCHAR(100) | YES | NULL | フリガナ |
| `nickname1` | VARCHAR(50) | YES | NULL | 愛称1 |
| `nickname2` | VARCHAR(50) | YES | NULL | 愛称2 |
| `template` | VARCHAR(50) | YES | NULL | テンプレート種別（例: SPORTS, CLINIC, SCHOOL）。Phase 2 は VARCHAR(50) のままアプリ層の enum 定数でバリデーション。メタデータ（カスタムフィールド等）が必要になった段階で `team_templates` テーブルを新設し FK へ移行 |
| `prefecture` | VARCHAR(10) | YES | NULL | 都道府県 |
| `city` | VARCHAR(50) | YES | NULL | 市区町村 |
| `description` | TEXT | YES | NULL | チーム説明 |
| `icon_url` | VARCHAR(512) | YES | NULL | アイコン画像R2キー（**実装: F01.6**）|
| `banner_url` | VARCHAR(512) | YES | NULL | バナー画像R2キー（**実装: F01.6**）|
| `homepage_url` | VARCHAR(512) | YES | NULL | チームホームページURL（`http://` または `https://` のみ許可。アプリ層でスキームバリデーション）|
| `established_date` | DATE | YES | NULL | 設立年月日。日不明時は `established_date_precision` を `YEAR` または `YEAR_MONTH` に設定し、月・日を `01` で埋める |
| `established_date_precision` | ENUM('YEAR', 'YEAR_MONTH', 'FULL') | YES | NULL | 設立日の精度。`established_date` が NULL のときは本カラムも NULL |
| `philosophy` | TEXT | YES | NULL | チーム理念・フィロソフィー（最大2000文字はアプリ層でバリデーション）|
| `profile_visibility` | JSON | YES | NULL | プロフィール項目ごとの公開可否フラグ（組織と同じ構造）。NULL は全項目非公開扱い |
| `visibility` | ENUM('PUBLIC', 'GUESTS_AND_ABOVE', 'SUPPORTERS_AND_ABOVE', 'MEMBERS_AND_ABOVE') | NO | 'GUESTS_AND_ABOVE' | 情報公開レベル（ロールベース設計）|
| `supporter_enabled` | BOOLEAN | NO | FALSE | サポーター（フォロー）登録機能の有効化フラグ。TRUE かつ visibility=PUBLIC の場合のみ招待コード不要でフォロー可能 |
| `supporter_name_disclosure` | ENUM('DISPLAY_NAME','REAL_NAME') | NO | 'DISPLAY_NAME' | サポーター向け氏名表示モード（**実装: F19.1**）。詳細: `F19.1_public_pages_identity_disclosure.md` §5.1.1 |
| `public_events_enabled` | BOOLEAN | NO | FALSE | 公開ページでチームイベント一覧を表示するか（**実装: F19.1**） |
| `map_embed_url` | VARCHAR(2048) | YES | NULL | Google Maps 埋め込み URL（**実装: F15.4 Phase 5 / F19.1**） |
| `archived_at` | DATETIME | YES | NULL | アーカイブ日時 |
| `deleted_at` | DATETIME | YES | NULL | 論理削除日時 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_teams_slug (slug)
INDEX idx_team_archived (archived_at)
INDEX idx_team_pref_city (prefecture, city)   -- 地域検索用
INDEX idx_team_name (name)
```

**制約・備考**
- 論理削除: `deleted_at DATETIME nullable`
- `slug`: 英小文字・数字・ハイフンのみ（パターン: `^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$`・3〜30文字）。アプリ層で正規表現バリデーション。**正準仕様（村方式）**: チーム作成時に**ユーザーが任意の slug を入力する**（村 `F17.1` の `VillageCreateRequest.slug` と同じ UX）。グローバル一意・予約語禁止。`name` からの自動生成は **「編集可能な提案プレフィル」に降格**する（FE が作成フォームで `name` から ASCII 変換した候補を初期表示し、ユーザーがそのまま採用・編集できる）。**強制的な連番付与（`team-000017` のような数値サフィックス）は廃止方針**。重複時は 400（後述 `slug-check` API でリアルタイム確認）を返し、ユーザーに別の slug 入力を促す。予約語・正準仕様の詳細は §6（`04_security_operations.md`）の「slug 正準仕様」節を参照
- 組織との多対多所属関係は `team_org_memberships` テーブルで管理する。チームは複数の組織に同時所属可能
- `visibility` は以下のロールベース設計を採用（V79.001 マイグレーションで ORGANIZATION_ONLY / PRIVATE から移行）:
  - `PUBLIC`: 未認証ユーザーも含め誰でも閲覧可
  - `GUESTS_AND_ABOVE`: GUEST 以上の所属メンバーすべてが閲覧可（直接所属ユーザー＋サポーター含む）
  - `SUPPORTERS_AND_ABOVE`: サポーター以上のロールを持つメンバーが閲覧可
  - `MEMBERS_AND_ABOVE`: 正規メンバー以上のロールを持つメンバーのみ閲覧可（サポーター・ゲストは除外）
- アーカイブトリガー: 全メンバーの最終ログインのうち最新が12ヶ月経過（README §アーカイブ規約参照）
- `template`: Phase 2 は VARCHAR(50) で運用（アプリ層 enum 定数でバリデーション）。`team_templates` への FK 移行は「テンプレートごとのメタデータが必要になった段階」でテンプレート管理 feature doc にて設計・実施する
- `homepage_url` / `established_date` / `philosophy` / `profile_visibility` の制約は `organizations` テーブルと同一。`profile_visibility` の JSON 構造・既知キー・非公開時の強制非公開ルールも同じ
- チームが `visibility = 'GUESTS_AND_ABOVE'` 以上の非公開設定の場合、プロフィール拡張項目は所属メンバーに対してのみ公開（`profile_visibility` の各項目フラグと AND 条件）

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
- `user_id` FK のカスケードポリシー: ユーザー退会は論理削除（`users.deleted_at`）で処理するため `ON DELETE RESTRICT` を採用（物理削除を防止）。一方、`team_blocks` / `organization_blocks` の `user_id` は `ON DELETE CASCADE`（ユーザー退会後のブロック記録は保持不要）

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

#### `ownership_transfer_offers`

オーナー委譲（ADMIN 権限移譲）の **承諾型オファー**。発行者が打診（PENDING 作成）し、指名相手の承諾（accept）で初めて委譲を実行する。2026-07-18 のマスター御裁可による承諾型化で新設。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BINARY(16) | NO | （UUIDv7・アプリ生成）| PK。**新規テーブルのため原則6に従い UUIDv7**（`UuidV7Entity` 継承）|
| `team_id` | BIGINT UNSIGNED | YES | NULL | 委譲対象がチームの場合に設定（組織委譲時は NULL）。**クロスドメインではなく team ドメイン内**だが FK は張らず INDEX（後述）|
| `organization_id` | BIGINT UNSIGNED | YES | NULL | 委譲対象が組織の場合に設定（チーム委譲時は NULL）|
| `issued_by` | BIGINT UNSIGNED | NO | — | 発行者（現 ADMIN）の user ID。FK なし（user は別ドメイン・原則1）|
| `target_user_id` | BIGINT UNSIGNED | NO | — | 指名相手（承諾できる唯一のユーザー）の user ID。FK なし・INDEX |
| `status` | VARCHAR(20) | NO | `'PENDING'` | `PENDING` / `ACCEPTED` / `DECLINED` / `EXPIRED` / `CANCELLED`。**VARCHAR + アプリ層検証**（ENUM にしない）|
| `expires_at` | DATETIME | NO | — | 有効期限（発行から7日を既定）。超過は EXPIRED |
| `accepted_at` | DATETIME | YES | NULL | 承諾日時（ACCEPTED 時のみ）|
| `resolved_at` | DATETIME | YES | NULL | 辞退/取消/期限確定の処理日時 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_oto_target_user (target_user_id, status)     -- 自分宛ての PENDING オファー一覧
INDEX idx_oto_team (team_id, status)                   -- チーム別 PENDING オファー
INDEX idx_oto_org (organization_id, status)            -- 組織別 PENDING オファー
```

**制約・備考**
- **主キーは UUIDv7（原則6）**: 本テーブルはテナント/ユーザーごとに行が増える新規テーブルのため、`UuidV7Entity` を継承し `id BINARY(16)` とする（マスタ例外・シングルトン例外のいずれにも該当しない）。
- **`team_id`/`organization_id` の XOR**: どちらか一方のみ非 NULL（`invite_tokens.chk_it_scope` と同方式の CHECK 制約 `chk_oto_scope` を張る）。
- **FK を張らない（原則1）**: `issued_by`/`target_user_id`（user ドメイン）はもちろん、`team_id`/`organization_id`（team/org ドメイン）も本テーブル（role ドメイン）から見れば別ドメイン参照のため FK なし・INDEX のみ。整合性はアプリ層で保証。
- **同一スコープに PENDING は 1 件まで**: `status='PENDING'` の重複打診をアプリ層で禁止（打診時 409）。DB レベルの部分 UNIQUE は MySQL 8.0 では関数インデックスで表現するが、運用頻度が低いためアプリ層チェックを一次とする。
- **既存 `invite_tokens` を流用しない理由**: `invite_tokens` は「非メンバーを新規参加させる」ための公開リンク/QR 用トークン（`role_id` で付与ロールを持ち、`used_count`/`max_uses` で多数参加を管理）である。オーナー委譲は「**既存メンバーのロールを入れ替える**」操作で意味論が異なり、`invite_tokens` に相乗りさせると join フローに特殊分岐が増えて認可が複雑化する。よって専用テーブル `ownership_transfer_offers`（新規＝原則6 で UUIDv7）を設ける方が整合的と判断した。
- **チーム/組織論理削除時**: 紐付く PENDING オファーを CANCELLED に一括更新（`invite_tokens` の一括失効と同方針）。

---

#### `team_blocks`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（ブロック対象; ON DELETE CASCADE）|
| `blocked_by` | BIGINT UNSIGNED | YES | NULL | FK → users（操作した ADMIN/DEPUTY_ADMIN; SET NULL on delete）|
| `reason` | VARCHAR(500) | YES | NULL | ブロック理由（ADMIN が任意入力・ブロック一覧画面で表示）|
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
- `reason`: ブロック理由の記録は任意。ブロック一覧 API（`GET /teams/{id}/blocks`）のレスポンスに含めて返す

---

#### `organization_blocks`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `organization_id` | BIGINT UNSIGNED | NO | — | FK → organizations |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（ブロック対象; ON DELETE CASCADE）|
| `blocked_by` | BIGINT UNSIGNED | YES | NULL | FK → users（操作した ADMIN/DEPUTY_ADMIN; SET NULL on delete）|
| `reason` | VARCHAR(500) | YES | NULL | ブロック理由（ADMIN が任意入力・ブロック一覧画面で表示）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_ob_org_user (organization_id, user_id)
INDEX idx_ob_organization_id (organization_id)
```

**制約・備考**
- `team_blocks` と同一の設計ポリシーを適用（ブロック対象は自己登録のみ制限・ADMIN手動付与は妨げない・`reason` は任意入力）
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

#### `organization_officers`

組織の役員情報を複数登録できる拡張プロフィールテーブル。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `organization_id` | BIGINT UNSIGNED | NO | — | FK → organizations（ON DELETE CASCADE）|
| `name` | VARCHAR(100) | NO | — | 役員名（最大100文字。氏名または表示名。個人情報としての本名登録を強制するものではない）|
| `title` | VARCHAR(100) | NO | — | 役職名（例: 代表、理事長、事務局長など。最大100文字）|
| `display_order` | INT UNSIGNED | NO | 0 | 並び順（小さいほど上位に表示。0 から連番で採番。バッチ再採番時は 10, 20, ... のギャップ運用も可）|
| `is_visible` | BOOLEAN | NO | TRUE | 個別表示可否（FALSE のときは一覧から除外）。ただし `organizations.profile_visibility.officers` が FALSE のときは全員非公開 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_org_officers_org (organization_id, display_order)
```

**制約・備考**
- 物理削除で管理。履歴は audit_logs で記録
- 1組織あたり最大 50 件（アプリ層で制限。超過時は `ORG_041` エラー/422）
- `name` / `title` はプレーンテキスト扱い。HTML/Markdown は一切解釈せず、フロント側で必ずエスケープして表示（XSS対策）
- `display_order` の一意性は保証しない（同値があっても `id` 昇順で安定ソート）
- 組織の物理削除時: ON DELETE CASCADE で自動削除。組織の論理削除時（`deleted_at` 設定）はアプリ層で削除しない（復元時に役員情報も復元されるべきため）
- **文字数カウント**: `name` / `title` の最大100文字は **Java `Character.codePointCount()` による「コードポイント数」** で判定する（絵文字・結合文字を1文字として扱う）。DB の VARCHAR(100) は utf8mb4 で最大100コードポイント分を格納可能。アプリ層バリデーションと DB 制約を一致させる
- **個人情報登録の責任**: 役員の `name` は組織運営上の代表者等の公開前提情報。組織管理者が本人の公開同意を得た上で登録する責任を負う（利用規約に記載）。システムは本人同意の技術的確認機能を持たない。プライバシー上の問題発生時は `DELETE` API で即時削除可能
- **reorder 時の競合**: `PUT /officers/reorder` リクエストは `orders` 配列で「当該組織の全役員 ID を網羅」する必要がある。並び替えリクエスト発行中に別 ADMIN が `POST` で新規追加した場合は、`orders` に含まれない既存役員が検出されて 422（`ORG_042`: stale reorder）を返す。クライアントは再取得して再度並び替えを行う

---

#### `team_officers`

チーム版。構造は `organization_officers` と同じ。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams（ON DELETE CASCADE）|
| `name` | VARCHAR(100) | NO | — | 役員名 |
| `title` | VARCHAR(100) | NO | — | 役職名 |
| `display_order` | INT UNSIGNED | NO | 0 | 並び順 |
| `is_visible` | BOOLEAN | NO | TRUE | 個別表示可否 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_team_officers_team (team_id, display_order)
```

**制約・備考**
- `organization_officers` と同じ。1チームあたり最大 50 件

---

#### `organization_custom_fields`

組織のフリー記述プロフィール項目（ラベル＋値）。設立経緯・活動拠点・メディア露出など、定型項目に収まらない情報を自由に追加できる。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `organization_id` | BIGINT UNSIGNED | NO | — | FK → organizations（ON DELETE CASCADE）|
| `label` | VARCHAR(100) | NO | — | 項目ラベル（最大100文字。例: 「活動拠点」「会員数」「提携企業」）|
| `value` | TEXT | NO | — | 項目値（最大1000文字をアプリ層でバリデーション）|
| `display_order` | INT UNSIGNED | NO | 0 | 並び順 |
| `is_visible` | BOOLEAN | NO | TRUE | 個別表示可否。`organizations.profile_visibility.custom_fields` が FALSE のときは全項目非公開 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_org_custom_fields_org (organization_id, display_order)
```

**制約・備考**
- 物理削除で管理。履歴は audit_logs で記録
- 1組織あたり最大 20 件（アプリ層で制限。超過時は `ORG_043` エラー/422）
- `label` の組織内重複は許容（UI上は同名でも並び順で区別）
- `label` / `value` はプレーンテキスト扱い。HTML/Markdown は一切解釈せず、フロント側で必ずエスケープして表示（XSS対策）
- `value` 内の URL はフロントエンドで `http(s)://` 始まりのみ自動リンク化（`javascript:` 等はリンク化しない。後述「セキュリティ考慮事項」参照）
- 組織の物理削除時: ON DELETE CASCADE で自動削除。論理削除時は残す（復元時に復元）
- **文字数カウント**: `label` 最大100文字・`value` 最大1000文字は `Character.codePointCount()` で判定（officers と同じ方式）
- **reorder 時の競合**: officers と同じ（`ORG_044`: stale reorder）

---

#### `team_custom_fields`

チーム版。構造は `organization_custom_fields` と同じ。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams（ON DELETE CASCADE）|
| `label` | VARCHAR(100) | NO | — | 項目ラベル |
| `value` | TEXT | NO | — | 項目値（最大1000文字）|
| `display_order` | INT UNSIGNED | NO | 0 | 並び順 |
| `is_visible` | BOOLEAN | NO | TRUE | 個別表示可否 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_team_custom_fields_team (team_id, display_order)
```

**制約・備考**
- `organization_custom_fields` と同じ。1チームあたり最大 20 件

---

### ER図（テキスト形式）
```
organizations (1) ──── (N) organizations        ※ parent_organization_id（自己参照）
teams (N) ──── (M) organizations                ※ via team_org_memberships（複数組織所属可）
users (N) ──── (M) teams / organizations        ※ MEMBER/SUPPORTER は via memberships（F00.5）、権限ロールは via user_roles
roles (1) ──── (N) user_roles                   ※ user_roles は権限ロール（ADMIN/DEPUTY_ADMIN/GUEST 等）専用（F00.5 Phase 4 以降）
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
organizations (1) ──── (N) organization_officers       ※ ON DELETE CASCADE
teams (1) ──── (N) team_officers                       ※ ON DELETE CASCADE
organizations (1) ──── (N) organization_custom_fields  ※ ON DELETE CASCADE
teams (1) ──── (N) team_custom_fields                  ※ ON DELETE CASCADE
```
