# appearance ドメイン ER 図

> このファイルは自動生成されます。手で編集しないでください。再生成: `cd backend && ./gradlew generateErDiagrams`

- テーブル数: 1
- ビュー数: 0

## ER 図

実線 = 外部キー、点線 = ID参照(FKなし)。図にはキー列（PK / FK / 参照推定列 / 単一列 UNIQUE）のみを描く。全列は下の「テーブル定義」を参照。

```mermaid
erDiagram
    appearance_settings {
        binary id PK
        bigint user_id FK, UK "ID参照"
    }
    users
    users ||..o| appearance_settings : "ID参照(FKなし) user_id"
```

## 推定不能参照

| テーブル | 列 | 理由 |
| --- | --- | --- |
| `appearance_settings` | `seasonal_theme_id` | 参照先テーブルを特定できない |

## テーブル定義

### `appearance_settings`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `binary(16)` | NO | （なし） |  |
| `user_id` | `bigint unsigned` | NO | （なし） |  |
| `theme` | `varchar(8)` | NO | `LIGHT` |  |
| `bg_color` | `varchar(32)` | NO | `#f3efe0` |  |
| `dark_bg_color` | `varchar(32)` | NO | `#18181b` |  |
| `seasonal_theme_id` | `bigint unsigned` | YES | NULL |  |
| `hide_chat_preview` | `tinyint(1)` | NO | `0` |  |
| `created_at` | `datetime(6)` | NO | （なし） |  |
| `updated_at` | `datetime(6)` | NO | （なし） |  |
