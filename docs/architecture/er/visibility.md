# visibility ドメイン ER 図

> このファイルは自動生成されます。手で編集しないでください。再生成: `cd backend && ./gradlew generateErDiagrams`

- テーブル数: 2
- ビュー数: 0

## ER 図

実線 = 外部キー、点線 = ID参照(FKなし)。図中の `FK` は実外部キーの列だけ（ID参照列は `FK` を付けず "ID参照" と注記）。図にはキー列（PK / FK / 参照推定列 / 単一列 UNIQUE）のみを描く。全列は下の「テーブル定義」を参照。

```mermaid
erDiagram
    visibility_template_rules {
        bigint id PK
        bigint template_id FK
    }
    visibility_templates {
        bigint id PK
        bigint owner_user_id "ID参照"
        varchar preset_key UK
    }
    users
    visibility_templates ||--o{ visibility_template_rules : "template_id"
    users |o..o{ visibility_templates : "ID参照(FKなし) owner_user_id"
```

## 被参照（他ドメインから）

このドメインのテーブルを参照している他ドメインの側（実 FK と ID参照のみ。多態参照と推定不能参照は含まない。同一ドメイン内の参照は上の図を参照）。

| 参照元ドメイン | 参照元テーブル | 列 | 種別 | 参照先テーブル |
| --- | --- | --- | --- | --- |
| [cms](cms.md) | `blog_posts` | `visibility_template_id` | ID参照 | `visibility_templates` |
| [recruitment](recruitment.md) | `recruitment_listings` | `visibility_template_id` | ID参照 | `visibility_templates` |
| [schedule](schedule.md) | `schedules` | `visibility_template_id` | ID参照 | `visibility_templates` |

## 推定不能参照

| テーブル | 列 | 理由 |
| --- | --- | --- |
| `visibility_template_rules` | `rule_target_id` | 参照先テーブルを特定できない |

## テーブル定義

### `visibility_template_rules`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `bigint unsigned` | NO | （なし） auto_increment |  |
| `template_id` | `bigint unsigned` | NO | （なし） |  |
| `rule_type` | `varchar(40)` | NO | （なし） |  |
| `rule_target_id` | `bigint unsigned` | YES | NULL |  |
| `rule_target_text` | `varchar(120)` | YES | NULL |  |
| `sort_order` | `int` | NO | `0` |  |
| `created_at` | `datetime(6)` | NO | `CURRENT_TIMESTAMP(6)` （式） |  |

### `visibility_templates`

| 列名 | 型 | NULL | 既定値 | コメント |
| --- | --- | --- | --- | --- |
| `id` | `bigint unsigned` | NO | （なし） auto_increment |  |
| `owner_user_id` | `bigint unsigned` | YES | NULL |  |
| `name` | `varchar(60)` | NO | （なし） |  |
| `description` | `varchar(240)` | YES | NULL |  |
| `icon_emoji` | `varchar(16)` | YES | NULL |  |
| `is_system_preset` | `tinyint(1)` | NO | `0` |  |
| `preset_key` | `varchar(64)` | YES | NULL |  |
| `created_at` | `datetime(6)` | NO | `CURRENT_TIMESTAMP(6)` （式） |  |
| `updated_at` | `datetime(6)` | NO | `CURRENT_TIMESTAMP(6)` （式） |  |
