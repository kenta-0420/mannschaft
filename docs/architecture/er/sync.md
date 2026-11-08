# sync ドメイン ER 図

> このファイルは自動生成されます。手で編集しないでください。再生成: `cd backend && ./gradlew generateErDiagrams`

- テーブル数: 1
- ビュー数: 0

## ER 図

実線 = 外部キー、点線 = ID参照(FKなし)。図中の `FK` は実外部キーの列だけ（ID参照列は `FK` を付けず "ID参照" と注記）。図にはキー列（PK / FK / 参照推定列 / 単一列 UNIQUE）のみを描く。全列は下の「テーブル定義」を参照。

```mermaid
erDiagram
    offline_sync_conflicts {
        bigint id PK
        bigint user_id "ID参照"
    }
    users
    users ||..o{ offline_sync_conflicts : "ID参照(FKなし) user_id"
```

## 被参照（他ドメインから）

このドメインのテーブルを参照している他ドメインの側（実 FK と ID参照のみ。多態参照と推定不能参照は含まない。同一ドメイン内の参照は上の図を参照）。

なし

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
