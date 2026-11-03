# sync ドメイン ER 図

> このファイルは自動生成されます。手で編集しないでください。再生成: `cd backend && ./gradlew generateErDiagrams`

- テーブル数: 1
- ビュー数: 0

## ER 図

実線 = 外部キー、点線 = ID参照(FKなし)。図にはキー列（PK / FK / 参照推定列 / 単一列 UNIQUE）のみを描く。全列は下の「テーブル定義」を参照。

```mermaid
erDiagram
    offline_sync_conflicts {
        bigint id PK
        bigint user_id FK "ID参照"
    }
    users
    users ||..o{ offline_sync_conflicts : "ID参照(FKなし) user_id"
```

## 多態参照（線を引かない）

| テーブル | 種別列 | ID列 |
| --- | --- | --- |
| `offline_sync_conflicts` | `resource_type` | `resource_id` |

## テーブル定義

### `offline_sync_conflicts`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `bigint unsigned` | NO | （なし） auto_increment |  |
| `user_id` | `bigint unsigned` | NO | （なし） |  |
| `resource_type` | `varchar(50)` | NO | （なし） |  |
| `resource_id` | `bigint unsigned` | NO | （なし） |  |
| `client_data` | `json` | NO | （なし） |  |
| `server_data` | `json` | NO | （なし） |  |
| `client_version` | `bigint` | NO | （なし） |  |
| `server_version` | `bigint` | NO | （なし） |  |
| `resolution` | `varchar(20)` | YES | NULL |  |
| `resolved_at` | `datetime` | YES | NULL |  |
| `created_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |
| `updated_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |
