# seal ドメイン ER 図

> このファイルは自動生成されます。手で編集しないでください。再生成: `cd backend && ./gradlew generateErDiagrams`

- テーブル数: 3
- ビュー数: 0

## ER 図

実線 = 外部キー、点線 = ID参照(FKなし)。図にはキー列（PK / FK / 参照推定列 / 単一列 UNIQUE）のみを描く。全列は下の「テーブル定義」を参照。

```mermaid
erDiagram
    electronic_seals {
        bigint id PK
        bigint user_id FK "ID参照"
    }
    seal_scope_defaults {
        bigint id PK
        bigint user_id FK "ID参照"
        bigint seal_id FK
    }
    seal_stamp_logs {
        bigint id PK
        bigint user_id FK "ID参照"
        bigint seal_id FK
    }
    users
    users ||..o{ electronic_seals : "ID参照(FKなし) user_id"
    electronic_seals ||--o{ seal_scope_defaults : "seal_id"
    users ||..o{ seal_scope_defaults : "ID参照(FKなし) user_id"
    electronic_seals ||--o{ seal_stamp_logs : "seal_id"
    users ||..o{ seal_stamp_logs : "ID参照(FKなし) user_id"
```

## 多態参照（線を引かない）

| テーブル | 種別列 | ID列 |
| --- | --- | --- |
| `seal_scope_defaults` | `scope_type` | `scope_id` |
| `seal_stamp_logs` | `target_type` | `target_id` |

## テーブル定義

### `electronic_seals`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `bigint unsigned` | NO | （なし） auto_increment |  |
| `user_id` | `bigint unsigned` | NO | （なし） |  |
| `variant` | `varchar(20)` | NO | （なし） |  |
| `display_text` | `varchar(20)` | NO | （なし） |  |
| `svg_data` | `text` | NO | （なし） |  |
| `seal_hash` | `char(64)` | NO | （なし） |  |
| `generation_version` | `int` | NO | `1` |  |
| `created_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |
| `updated_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |
| `deleted_at` | `datetime` | YES | NULL |  |

### `seal_scope_defaults`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `bigint unsigned` | NO | （なし） auto_increment |  |
| `user_id` | `bigint unsigned` | NO | （なし） |  |
| `scope_type` | `varchar(20)` | NO | `DEFAULT` |  |
| `scope_id` | `bigint unsigned` | YES | NULL |  |
| `seal_id` | `bigint unsigned` | NO | （なし） |  |
| `created_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |
| `updated_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |

### `seal_stamp_logs`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `bigint unsigned` | NO | （なし） auto_increment |  |
| `user_id` | `bigint unsigned` | NO | （なし） |  |
| `seal_id` | `bigint unsigned` | NO | （なし） |  |
| `seal_hash_at_stamp` | `char(64)` | NO | （なし） |  |
| `target_type` | `varchar(30)` | NO | （なし） |  |
| `target_id` | `bigint unsigned` | NO | （なし） |  |
| `stamp_document_hash` | `char(64)` | YES | NULL |  |
| `is_revoked` | `tinyint(1)` | NO | `0` |  |
| `revoked_at` | `datetime` | YES | NULL |  |
| `stamped_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |
| `created_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |
