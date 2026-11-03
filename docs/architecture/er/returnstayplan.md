# returnstayplan ドメイン ER 図

> このファイルは自動生成されます。手で編集しないでください。再生成: `cd backend && ./gradlew generateErDiagrams`

- テーブル数: 3
- ビュー数: 0

## ER 図

実線 = 外部キー、点線 = ID参照(FKなし)。図にはキー列（PK / FK / 参照推定列 / 単一列 UNIQUE）のみを描く。全列は下の「テーブル定義」を参照。

```mermaid
erDiagram
    return_stay_plan_owner_locks {
        binary id PK
        bigint owner_user_id FK, UK "ID参照"
    }
    return_stay_plan_team_visibilities {
        binary id PK
        binary plan_id FK
        bigint team_id FK "ID参照"
    }
    return_stay_plans {
        binary id PK
        bigint owner_user_id FK "ID参照"
    }
    teams
    users
    users ||..o| return_stay_plan_owner_locks : "ID参照(FKなし) owner_user_id"
    return_stay_plans ||--o{ return_stay_plan_team_visibilities : "plan_id"
    teams ||..o{ return_stay_plan_team_visibilities : "ID参照(FKなし) team_id"
    users ||..o{ return_stay_plans : "ID参照(FKなし) owner_user_id"
```

## テーブル定義

### `return_stay_plan_owner_locks`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `binary(16)` | NO | （なし） |  |
| `owner_user_id` | `bigint unsigned` | NO | （なし） |  |
| `created_at` | `datetime(6)` | NO | `CURRENT_TIMESTAMP(6)` （式） |  |
| `updated_at` | `datetime(6)` | NO | `CURRENT_TIMESTAMP(6)` （式） |  |

### `return_stay_plan_team_visibilities`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `binary(16)` | NO | （なし） |  |
| `plan_id` | `binary(16)` | NO | （なし） |  |
| `team_id` | `bigint unsigned` | NO | （なし） |  |
| `created_at` | `datetime(6)` | NO | `CURRENT_TIMESTAMP(6)` （式） |  |
| `updated_at` | `datetime(6)` | NO | `CURRENT_TIMESTAMP(6)` （式） |  |

### `return_stay_plans`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `binary(16)` | NO | （なし） |  |
| `owner_user_id` | `bigint unsigned` | NO | （なし） |  |
| `plan_type` | `varchar(20)` | NO | （なし） |  |
| `is_published` | `tinyint(1)` | NO | `0` |  |
| `country_code` | `char(2)` | NO | （なし） |  |
| `prefecture_code` | `char(2)` | YES | NULL |  |
| `region_name` | `varchar(100)` | YES | NULL |  |
| `timezone` | `varchar(64)` | NO | （なし） |  |
| `start_date` | `date` | NO | （なし） |  |
| `end_date` | `date` | NO | （なし） |  |
| `version` | `bigint` | NO | `0` |  |
| `created_at` | `datetime(6)` | NO | `CURRENT_TIMESTAMP(6)` （式） |  |
| `updated_at` | `datetime(6)` | NO | `CURRENT_TIMESTAMP(6)` （式） |  |
