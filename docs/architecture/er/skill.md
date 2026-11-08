# skill ドメイン ER 図

> このファイルは自動生成されます。手で編集しないでください。再生成: `cd backend && ./gradlew generateErDiagrams`

- テーブル数: 3
- ビュー数: 0

## ER 図

実線 = 外部キー、点線 = ID参照(FKなし)。図中の `FK` は実外部キーの列だけ（ID参照列は `FK` を付けず "ID参照" と注記）。図にはキー列（PK / FK / 参照推定列 / 単一列 UNIQUE）のみを描く。全列は下の「テーブル定義」を参照。

```mermaid
erDiagram
    member_skills {
        bigint id PK
        bigint skill_category_id FK
        bigint user_id "ID参照"
        bigint verified_by "ID参照"
    }
    skill_categories {
        bigint id PK
        bigint created_by "ID参照"
    }
    skill_expiry_notifications {
        bigint id PK
        bigint member_skill_id FK
    }
    users
    skill_categories |o--o{ member_skills : "skill_category_id"
    users ||..o{ member_skills : "ID参照(FKなし) user_id"
    users |o..o{ member_skills : "ID参照(FKなし) verified_by"
    users ||..o{ skill_categories : "ID参照(FKなし) created_by"
    member_skills ||--o{ skill_expiry_notifications : "member_skill_id"
```

## 被参照（他ドメインから）

このドメインのテーブルを参照している他ドメインの側（実 FK と ID参照のみ。多態参照と推定不能参照は含まない。同一ドメイン内の参照は上の図を参照）。

なし

## 多態参照（線を引かない）

| テーブル | 種別列 | ID列 |
| --- | --- | --- |
| `member_skills` | `scope_type` | `scope_id` |
| `skill_categories` | `scope_type` | `scope_id` |

## テーブル定義

### `member_skills`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `bigint unsigned` | NO | （なし） auto_increment |  |
| `skill_category_id` | `bigint unsigned` | YES | NULL |  |
| `user_id` | `bigint unsigned` | NO | （なし） |  |
| `scope_type` | `varchar(50)` | NO | （なし） |  |
| `scope_id` | `bigint unsigned` | NO | （なし） |  |
| `name` | `varchar(100)` | NO | （なし） |  |
| `issuer` | `varchar(200)` | YES | NULL |  |
| `credential_number` | `varchar(100)` | YES | NULL |  |
| `acquired_on` | `date` | YES | NULL |  |
| `expires_at` | `date` | YES | NULL |  |
| `status` | `varchar(20)` | NO | `PENDING_REVIEW` |  |
| `certificate_s3_key` | `varchar(500)` | YES | NULL |  |
| `verified_at` | `datetime` | YES | NULL |  |
| `verified_by` | `bigint unsigned` | YES | NULL |  |
| `version` | `bigint` | NO | `0` |  |
| `created_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |
| `updated_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |
| `deleted_at` | `datetime` | YES | NULL |  |

### `skill_categories`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `bigint unsigned` | NO | （なし） auto_increment |  |
| `scope_type` | `varchar(50)` | NO | （なし） |  |
| `scope_id` | `bigint unsigned` | NO | （なし） |  |
| `name` | `varchar(100)` | NO | （なし） |  |
| `description` | `varchar(500)` | YES | NULL |  |
| `icon` | `varchar(50)` | YES | NULL |  |
| `sort_order` | `int` | NO | `0` |  |
| `is_active` | `tinyint(1)` | NO | `1` |  |
| `created_by` | `bigint unsigned` | NO | （なし） |  |
| `version` | `bigint` | NO | `0` |  |
| `created_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |
| `updated_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |
| `deleted_at` | `datetime` | YES | NULL |  |

### `skill_expiry_notifications`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `bigint unsigned` | NO | （なし） auto_increment |  |
| `member_skill_id` | `bigint unsigned` | NO | （なし） |  |
| `notification_type` | `varchar(20)` | NO | （なし） |  |
| `sent_at` | `datetime` | NO | （なし） |  |
