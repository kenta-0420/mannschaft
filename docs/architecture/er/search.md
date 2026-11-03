# search ドメイン ER 図

> このファイルは自動生成されます。手で編集しないでください。再生成: `cd backend && ./gradlew generateErDiagrams`

- テーブル数: 2
- ビュー数: 0

## ER 図

実線 = 外部キー、点線 = ID参照(FKなし)。図にはキー列（PK / FK / 参照推定列 / 単一列 UNIQUE）のみを描く。全列は下の「テーブル定義」を参照。

```mermaid
erDiagram
    search_histories {
        bigint id PK
        bigint user_id FK "ID参照"
    }
    search_saved_queries {
        bigint id PK
        bigint user_id FK "ID参照"
    }
    users
    users ||..o{ search_histories : "ID参照(FKなし) user_id"
    users ||..o{ search_saved_queries : "ID参照(FKなし) user_id"
```

## テーブル定義

### `search_histories`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `bigint unsigned` | NO | （なし） auto_increment |  |
| `user_id` | `bigint unsigned` | NO | （なし） |  |
| `query` | `varchar(100)` | NO | （なし） |  |
| `searched_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |

### `search_saved_queries`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `bigint unsigned` | NO | （なし） auto_increment |  |
| `user_id` | `bigint unsigned` | NO | （なし） |  |
| `name` | `varchar(50)` | NO | （なし） |  |
| `query_params` | `json` | NO | （なし） |  |
| `created_at` | `datetime` | NO | `CURRENT_TIMESTAMP` （式） |  |
