# residencestatus ドメイン ER 図

> このファイルは自動生成されます。手で編集しないでください。再生成: `cd backend && ./gradlew generateErDiagrams`

- テーブル数: 5
- ビュー数: 0

## ER 図

実線 = 外部キー、点線 = ID参照(FKなし)。図中の `FK` は実外部キーの列だけ（ID参照列は `FK` を付けず "ID参照" と注記）。図にはキー列（PK / FK / 参照推定列 / 単一列 UNIQUE）のみを描く。全列は下の「テーブル定義」を参照。

```mermaid
erDiagram
    annual_review_responses {
        binary id PK
        bigint organization_id "ID参照"
        binary annual_review_id FK
        bigint dwelling_unit_id "ID参照"
        bigint respondent_user_id "ID参照"
    }
    annual_reviews {
        binary id PK
        bigint organization_id "ID参照"
        bigint created_by "ID参照"
    }
    monitoring_committee_visits {
        binary id PK
        bigint organization_id "ID参照"
        bigint dwelling_unit_id "ID参照"
        bigint subject_user_id "ID参照"
        bigint committee_id "ID参照"
        bigint visitor_user_id "ID参照"
    }
    org_wide_safety_checks {
        binary id PK
        bigint organization_id "ID参照"
        bigint safety_check_id "ID参照"
        bigint triggered_by "ID参照"
    }
    resident_activity_snapshots {
        binary id PK
        bigint organization_id "ID参照"
        bigint dwelling_unit_id "ID参照"
        bigint subject_user_id "ID参照"
    }
    committees
    dwelling_units
    organizations
    safety_checks
    users
    annual_reviews ||--o{ annual_review_responses : "annual_review_id"
    dwelling_units ||..o{ annual_review_responses : "ID参照(FKなし) dwelling_unit_id"
    organizations ||..o{ annual_review_responses : "ID参照(FKなし) organization_id"
    users ||..o{ annual_review_responses : "ID参照(FKなし) respondent_user_id"
    organizations ||..o{ annual_reviews : "ID参照(FKなし) organization_id"
    users |o..o{ annual_reviews : "ID参照(FKなし) created_by"
    committees ||..o{ monitoring_committee_visits : "ID参照(FKなし) committee_id"
    dwelling_units ||..o{ monitoring_committee_visits : "ID参照(FKなし) dwelling_unit_id"
    organizations ||..o{ monitoring_committee_visits : "ID参照(FKなし) organization_id"
    users ||..o{ monitoring_committee_visits : "ID参照(FKなし) subject_user_id"
    users ||..o{ monitoring_committee_visits : "ID参照(FKなし) visitor_user_id"
    organizations ||..o{ org_wide_safety_checks : "ID参照(FKなし) organization_id"
    safety_checks ||..o{ org_wide_safety_checks : "ID参照(FKなし) safety_check_id"
    users ||..o{ org_wide_safety_checks : "ID参照(FKなし) triggered_by"
    dwelling_units ||..o{ resident_activity_snapshots : "ID参照(FKなし) dwelling_unit_id"
    organizations ||..o{ resident_activity_snapshots : "ID参照(FKなし) organization_id"
    users ||..o{ resident_activity_snapshots : "ID参照(FKなし) subject_user_id"
```

## 被参照（他ドメインから）

このドメインのテーブルを参照している他ドメインの側（実 FK と ID参照のみ。多態参照と推定不能参照は含まない。同一ドメイン内の参照は上の図を参照）。

なし

## 推定不能参照

| テーブル | 列 | 理由 |
| --- | --- | --- |
| `annual_review_responses` | `resident_registry_id` | 参照先テーブルを特定できない |
| `monitoring_committee_visits` | `consent_covenant_id` | 参照先テーブルを特定できない |
| `monitoring_committee_visits` | `resident_registry_id` | 参照先テーブルを特定できない |
| `resident_activity_snapshots` | `resident_registry_id` | 参照先テーブルを特定できない |

## テーブル定義

### `annual_review_responses`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `binary(16)` | NO | （なし） |  |
| `organization_id` | `bigint unsigned` | NO | （なし） |  |
| `annual_review_id` | `binary(16)` | NO | （なし） |  |
| `dwelling_unit_id` | `bigint unsigned` | NO | （なし） |  |
| `resident_registry_id` | `bigint unsigned` | NO | （なし） |  |
| `respondent_user_id` | `bigint unsigned` | NO | （なし） |  |
| `residence_state` | `varchar(30)` | NO | `UNRESPONDED` |  |
| `contact_phone_verified` | `tinyint(1)` | NO | `0` |  |
| `contact_email_verified` | `tinyint(1)` | NO | `0` |  |
| `emergency_contact_verified` | `tinyint(1)` | NO | `0` |  |
| `responded_at` | `datetime(6)` | YES | NULL |  |
| `note` | `text` | YES | NULL |  |
| `created_at` | `datetime(6)` | NO | `CURRENT_TIMESTAMP(6)` （式） |  |
| `updated_at` | `datetime(6)` | NO | `CURRENT_TIMESTAMP(6)` （式） |  |
| `deleted_at` | `datetime(6)` | YES | NULL |  |

### `annual_reviews`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `binary(16)` | NO | （なし） |  |
| `organization_id` | `bigint unsigned` | NO | （なし） |  |
| `review_year` | `smallint unsigned` | NO | （なし） |  |
| `started_at` | `datetime(6)` | NO | （なし） |  |
| `deadline_at` | `datetime(6)` | NO | （なし） |  |
| `closed_at` | `datetime(6)` | YES | NULL |  |
| `target_count` | `int unsigned` | NO | `0` |  |
| `response_count` | `int unsigned` | NO | `0` |  |
| `created_by` | `bigint unsigned` | YES | NULL |  |
| `created_at` | `datetime(6)` | NO | `CURRENT_TIMESTAMP(6)` （式） |  |
| `updated_at` | `datetime(6)` | NO | `CURRENT_TIMESTAMP(6)` （式） |  |
| `deleted_at` | `datetime(6)` | YES | NULL |  |

### `monitoring_committee_visits`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `binary(16)` | NO | （なし） |  |
| `organization_id` | `bigint unsigned` | NO | （なし） |  |
| `dwelling_unit_id` | `bigint unsigned` | NO | （なし） |  |
| `resident_registry_id` | `bigint unsigned` | NO | （なし） |  |
| `subject_user_id` | `bigint unsigned` | NO | （なし） |  |
| `committee_id` | `bigint unsigned` | NO | （なし） |  |
| `visitor_user_id` | `bigint unsigned` | NO | （なし） |  |
| `visited_at` | `datetime(6)` | NO | （なし） |  |
| `contact_result` | `varchar(20)` | NO | （なし） |  |
| `consideration_memo_encrypted` | `text` | YES | NULL |  |
| `next_visit_recommended_at` | `date` | YES | NULL |  |
| `consent_covenant_id` | `binary(16)` | YES | NULL |  |
| `created_at` | `datetime(6)` | NO | `CURRENT_TIMESTAMP(6)` （式） |  |
| `updated_at` | `datetime(6)` | NO | `CURRENT_TIMESTAMP(6)` （式） |  |
| `deleted_at` | `datetime(6)` | YES | NULL |  |

### `org_wide_safety_checks`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `binary(16)` | NO | （なし） |  |
| `organization_id` | `bigint unsigned` | NO | （なし） |  |
| `safety_check_id` | `bigint unsigned` | NO | （なし） |  |
| `triggered_by` | `bigint unsigned` | NO | （なし） |  |
| `triggered_at` | `datetime(6)` | NO | （なし） |  |
| `trigger_reason` | `varchar(200)` | YES | NULL |  |
| `closed_at` | `datetime(6)` | YES | NULL |  |
| `created_at` | `datetime(6)` | NO | `CURRENT_TIMESTAMP(6)` （式） |  |
| `updated_at` | `datetime(6)` | NO | `CURRENT_TIMESTAMP(6)` （式） |  |
| `deleted_at` | `datetime(6)` | YES | NULL |  |

### `resident_activity_snapshots`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `binary(16)` | NO | （なし） |  |
| `organization_id` | `bigint unsigned` | NO | （なし） |  |
| `dwelling_unit_id` | `bigint unsigned` | NO | （なし） |  |
| `resident_registry_id` | `bigint unsigned` | NO | （なし） |  |
| `subject_user_id` | `bigint unsigned` | NO | （なし） |  |
| `snapshot_date` | `date` | NO | （なし） |  |
| `activity_score_total` | `smallint unsigned` | NO | `0` |  |
| `activity_breakdown_json` | `json` | YES | NULL |  |
| `created_at` | `datetime(6)` | NO | `CURRENT_TIMESTAMP(6)` （式） |  |
| `updated_at` | `datetime(6)` | NO | `CURRENT_TIMESTAMP(6)` （式） |  |
| `deleted_at` | `datetime(6)` | YES | NULL |  |
