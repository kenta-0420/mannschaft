# organization ドメイン ER 図

> このファイルは自動生成されます。手で編集しないでください。再生成: `cd backend && ./gradlew generateErDiagrams`

- テーブル数: 6
- ビュー数: 0

## ER 図

実線 = 外部キー、点線 = ID参照(FKなし)。図にはキー列（PK / FK / 参照推定列 / 単一列 UNIQUE）のみを描く。全列は下の「テーブル定義」を参照。

```mermaid
erDiagram
    org_team_groups {
        binary id PK
        bigint organization_id FK "ID参照"
        bigint created_by FK "ID参照"
        bigint updated_by FK "ID参照"
    }
    organization_blocks {
        bigint id PK
        bigint organization_id FK "ID参照"
        bigint user_id FK "ID参照"
        bigint blocked_by FK "ID参照"
    }
    organization_custom_fields {
        bigint id PK
        bigint organization_id FK "ID参照"
    }
    organization_officers {
        bigint id PK
        bigint organization_id FK "ID参照"
    }
    organization_slug_history {
        binary id PK
        bigint organization_id FK "ID参照"
        varchar old_slug UK
    }
    organizations {
        bigint id PK
        bigint parent_organization_id FK
        varchar slug UK
    }
    users
    organizations ||..o{ org_team_groups : "ID参照(FKなし) organization_id"
    users |o..o{ org_team_groups : "ID参照(FKなし) created_by"
    users |o..o{ org_team_groups : "ID参照(FKなし) updated_by"
    organizations ||..o{ organization_blocks : "ID参照(FKなし) organization_id"
    users ||..o{ organization_blocks : "ID参照(FKなし) blocked_by"
    users ||..o{ organization_blocks : "ID参照(FKなし) user_id"
    organizations ||..o{ organization_custom_fields : "ID参照(FKなし) organization_id"
    organizations ||..o{ organization_officers : "ID参照(FKなし) organization_id"
    organizations ||..o{ organization_slug_history : "ID参照(FKなし) organization_id"
    organizations |o--o{ organizations : "parent_organization_id"
```

## テーブル定義

### `org_team_groups`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `binary(16)` | NO | （なし） | UUIDv7 |
| `organization_id` | `bigint unsigned` | NO | （なし） | 組織ID（クロスドメインFKなし） |
| `name` | `varchar(50)` | NO | （なし） | 表示名 |
| `description` | `varchar(200)` | YES | NULL | 補足説明 |
| `sort_order` | `int` | NO | （なし） | 並び順（昇順） |
| `created_by` | `bigint unsigned` | YES | NULL |  |
| `updated_by` | `bigint unsigned` | YES | NULL |  |
| `created_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |
| `updated_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |
| `deleted_at` | `datetime` | YES | NULL |  |
| `active_name` | `varchar(50)` | YES | NULL | 生存行だけの名前（一意制約用の生成列） |

### `organization_blocks`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `bigint unsigned` | NO | （なし） auto_increment |  |
| `organization_id` | `bigint unsigned` | NO | （なし） |  |
| `user_id` | `bigint unsigned` | NO | （なし） |  |
| `blocked_by` | `bigint unsigned` | NO | （なし） |  |
| `reason` | `varchar(500)` | YES | NULL |  |
| `created_at` | `datetime` | NO | （なし） |  |

### `organization_custom_fields`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `bigint unsigned` | NO | （なし） auto_increment |  |
| `organization_id` | `bigint unsigned` | NO | （なし） | FK → organizations |
| `label` | `varchar(100)` | NO | （なし） | 項目ラベル |
| `value` | `text` | NO | （なし） | 項目値（最大1000文字） |
| `display_order` | `int unsigned` | NO | `0` | 並び順 |
| `is_visible` | `tinyint(1)` | NO | `1` | 個別表示可否 |
| `created_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |
| `updated_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |

### `organization_officers`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `bigint unsigned` | NO | （なし） auto_increment |  |
| `organization_id` | `bigint unsigned` | NO | （なし） | FK → organizations |
| `name` | `varchar(100)` | NO | （なし） | 役員名 |
| `title` | `varchar(100)` | NO | （なし） | 役職名 |
| `display_order` | `int unsigned` | NO | `0` | 並び順 |
| `is_visible` | `tinyint(1)` | NO | `1` | 個別表示可否 |
| `created_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |
| `updated_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |

### `organization_slug_history`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `binary(16)` | NO | （なし） | UUIDv7（原則6） |
| `organization_id` | `bigint unsigned` | NO | （なし） | リネーム対象組織（organizations.id・FK なし／原則1） |
| `old_slug` | `varchar(30)` | NO | （なし） | リネーム前の旧 slug（恒久予約・301 解決のキー） |
| `created_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） | リネーム実施日時 |

### `organizations`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `bigint unsigned` | NO | （なし） auto_increment |  |
| `name` | `varchar(100)` | NO | （なし） |  |
| `icon_url` | `varchar(512)` | YES | NULL | アイコン画像R2キー |
| `banner_url` | `varchar(512)` | YES | NULL | バナー画像R2キー |
| `name_kana` | `varchar(100)` | YES | NULL |  |
| `nickname1` | `varchar(50)` | YES | NULL |  |
| `nickname2` | `varchar(50)` | YES | NULL |  |
| `org_type` | `enum('GOVERNMENT','MUNICIPALITY','COMPANY','HOSPITAL','ASSOCIATION','SCHOOL','NPO','COMMUNITY','OTHER')` | NO | `OTHER` |  |
| `parent_organization_id` | `bigint unsigned` | YES | NULL |  |
| `prefecture` | `varchar(20)` | YES | NULL |  |
| `city` | `varchar(50)` | YES | NULL |  |
| `visibility` | `varchar(20)` | NO | `PUBLIC` |  |
| `hierarchy_visibility` | `varchar(20)` | NO | `NONE` |  |
| `supporter_enabled` | `tinyint(1)` | NO | `1` |  |
| `version` | `bigint` | NO | `0` |  |
| `archived_at` | `datetime` | YES | NULL |  |
| `deleted_at` | `datetime` | YES | NULL |  |
| `created_at` | `datetime` | NO | （なし） |  |
| `updated_at` | `datetime` | NO | （なし） |  |
| `homepage_url` | `varchar(512)` | YES | NULL | ホームページURL（http/https のみ） |
| `established_date` | `date` | YES | NULL | 設立年月日（日不明時は01で埋める） |
| `established_date_precision` | `enum('YEAR','YEAR_MONTH','FULL')` | YES | NULL | 設立日精度 |
| `philosophy` | `text` | YES | NULL | 組織理念・フィロソフィー（最大2000文字） |
| `profile_visibility` | `json` | YES | NULL | プロフィール項目ごとの公開可否フラグ |
| `supporter_name_disclosure` | `enum('DISPLAY_NAME','REAL_NAME')` | NO | `DISPLAY_NAME` | サポーター向け氏名表示モード（F19.1 Phase 2 で機能活性化、Phase 1 ではカラム追加のみ） |
| `map_embed_url` | `varchar(2048)` | YES | NULL | Google Maps 等の埋め込み URL（F19.1 Phase 1 で organizations の公開ページに表示） |
| `elderly_care_threshold_age` | `tinyint` | NO | `75` | 高齢ケア提案のしきい値年齢（組織別カスタマイズ可） |
| `timeline_posts_public` | `tinyint(1)` | NO | `0` | タイムライン投稿を公開ページに表示するか |
| `public_events_enabled` | `tinyint(1)` | NO | `0` | イベントを公開ページに表示するか（当初「常時公開」としていたが 2026-05-23 方針変更） |
| `slug` | `varchar(30)` | NO | （なし） |  |
| `lifecycle_status` | `varchar(20)` | NO | `ACTIVE` | PROVISIONED/ACTIVE。承諾前の事前作成状態 |
| `name_trimmed` | `varchar(100)` | YES | NULL | 柱③-A 同名確認フロー用: TRIM(name)の生成列（索引対象） |
| `team_application_enabled` | `tinyint(1)` | NO | `0` | チームからの加盟申請を受け付けるか（既定 off） |
| `team_groups_enabled` | `tinyint(1)` | NO | `0` | チームグループ機能を使うか |
| `team_application_group_mode` | `varchar(10)` | NO | `OFF` | 申請時のグループ選択: OFF / OPTIONAL / REQUIRED |
| `team_application_guidance` | `varchar(500)` | YES | NULL | 申請フォームに表示する案内文 |
| `closure_generation` | `bigint` | NO | `0` | 閉鎖の世代番号。アーカイブ・論理削除のたびに +1（解除・復元では変えない） |
