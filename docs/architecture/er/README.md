# ER 図（実スキーマからの自動生成）

> このディレクトリは自動生成されます。手で編集しないでください。

Flyway を全適用した MySQL の `information_schema` から、ドメイン単位の mermaid `erDiagram` と全列定義表を生成したものです。

**`docs/features` 等の手描き図は設計意図、本ディレクトリの図は現実のスキーマ**です。両者が食い違う場合は本ディレクトリが現状を表します。

## 再生成と照合

```
cd backend && ./gradlew generateErDiagrams
cd backend && ./gradlew checkErDiagrams
```

Docker が必要です。CI（`er-diagram-drift-check`）は migration・Entity・生成器の変更時に照合し、再生成漏れを検出します。

## 凡例

- 図中の `FK` マーカーは information_schema 上の**実外部キー列だけ**に付く。ID参照（推定）の列には付かず、`"ID参照"` と注記する。
- 列定義表の「コメント」列は migration の COMMENT をそのまま転記したもの。「FK → teams」とあっても実 FK とは限らない。
- あるテーブルを参照している側を探すには、そのテーブルが属するドメインのページの「被参照」節を見る。多態参照・推定不能参照は被参照に出ないので、網羅確認では各ファイルの表を別途見ること。`_unmapped`（ドメイン未割当）のテーブルは通常のドメインと同じく、参照元としても参照先としても被参照に載る。
- 実線: 外部キー（FK）。子側の列が NULL 可なら親側 `0..1`、NOT NULL なら `1`。
- 点線: ID参照(FKなし)。`user_id` / `*_user_id` / `*_by` は `users`、`<x>_id` は `x` の複数形テーブルへ、**列の DATA_TYPE が参照先の主キーと一致する場合だけ**推定して引く。
- 多態参照（同一テーブルに `<p>_type` と `<p>_id` がある）は線を引かず、各ファイルの表に列挙する。
- 推定できない `*_id` は各ファイルの「推定不能参照」表に列挙する。
- mermaid は同一テーブルの自己参照を複数描けず1本に畳むため、自己参照は各ページの「自己参照」表を正とする。
- 参照先が他ドメインの場合は、列を持たないスタブ実体として描く。
- 図にはキー列（PK / FK / 参照推定列 / 単一列 UNIQUE=UK）のみを描く。全列は各ファイルの表。
- ビューは図に描かず、各ファイルの「ビュー」節に列挙する。

## ドメイン一覧

| ドメイン | テーブル数 | ビュー数 |
| --- | --- | --- |
| [actionmemo](actionmemo.md) | 4 | 0 |
| [activity](activity.md) | 6 | 0 |
| [admin](admin.md) | 9 | 0 |
| [advertising](advertising.md) | 29 | 0 |
| [analytics](analytics.md) | 11 | 0 |
| [appearance](appearance.md) | 1 | 0 |
| [auth](auth.md) | 17 | 0 |
| [billing](billing.md) | 26 | 0 |
| [budget](budget.md) | 7 | 0 |
| [bulletin](bulletin.md) | 7 | 0 |
| [chart](chart.md) | 10 | 0 |
| [chat](chat.md) | 6 | 0 |
| [circulation](circulation.md) | 6 | 0 |
| [cms](cms.md) | 13 | 0 |
| [committee](committee.md) | 4 | 0 |
| [common](common.md) | 7 | 0 |
| [contact](contact.md) | 3 | 0 |
| [corkboard](corkboard.md) | 4 | 0 |
| [cspreport](cspreport.md) | 1 | 0 |
| [dashboard](dashboard.md) | 6 | 0 |
| [diagnosis](diagnosis.md) | 3 | 0 |
| [digest](digest.md) | 2 | 0 |
| [directmail](directmail.md) | 4 | 0 |
| [disclosure](disclosure.md) | 4 | 0 |
| [equipment](equipment.md) | 2 | 0 |
| [errorreport](errorreport.md) | 4 | 0 |
| [event](event.md) | 10 | 0 |
| [facility](facility.md) | 9 | 0 |
| [family](family.md) | 11 | 0 |
| [faq](faq.md) | 1 | 0 |
| [favorite](favorite.md) | 1 | 0 |
| [files](files.md) | 2 | 0 |
| [filesharing](filesharing.md) | 8 | 0 |
| [forms](forms.md) | 5 | 0 |
| [gallery](gallery.md) | 2 | 0 |
| [gamification](gamification.md) | 7 | 0 |
| [gdpr](gdpr.md) | 4 | 0 |
| [inbox](inbox.md) | 3 | 0 |
| [incident](incident.md) | 8 | 0 |
| [incidentbanner](incidentbanner.md) | 2 | 0 |
| [jobmatching](jobmatching.md) | 5 | 0 |
| [joinrequest](joinrequest.md) | 1 | 0 |
| [knowledgebase](knowledgebase.md) | 6 | 0 |
| [line](line.md) | 4 | 0 |
| [loyalty](loyalty.md) | 6 | 0 |
| [mail](mail.md) | 1 | 0 |
| [match](match.md) | 7 | 0 |
| [matching](matching.md) | 10 | 0 |
| [member](member.md) | 5 | 0 |
| [memberinfo](memberinfo.md) | 2 | 0 |
| [membership](membership.md) | 7 | 0 |
| [mention](mention.md) | 1 | 0 |
| [moderation](moderation.md) | 10 | 0 |
| [navsettings](navsettings.md) | 2 | 0 |
| [notification](notification.md) | 20 | 0 |
| [onboarding](onboarding.md) | 5 | 0 |
| [organization](organization.md) | 6 | 0 |
| [parking](parking.md) | 14 | 0 |
| [payment](payment.md) | 22 | 0 |
| [performance](performance.md) | 4 | 0 |
| [pointcard](pointcard.md) | 8 | 0 |
| [promotion](promotion.md) | 9 | 0 |
| [property](property.md) | 4 | 0 |
| [provisioning](provisioning.md) | 1 | 0 |
| [proxy](proxy.md) | 3 | 0 |
| [proxyvote](proxyvote.md) | 6 | 0 |
| [publicview](publicview.md) | 3 | 0 |
| [queue](queue.md) | 6 | 0 |
| [quickmemo](quickmemo.md) | 9 | 0 |
| [ranch](ranch.md) | 18 | 0 |
| [receipt](receipt.md) | 7 | 0 |
| [recruitment](recruitment.md) | 17 | 0 |
| [reflection](reflection.md) | 10 | 0 |
| [repairplan](repairplan.md) | 9 | 0 |
| [reservation](reservation.md) | 17 | 0 |
| [residencestatus](residencestatus.md) | 5 | 0 |
| [resident](resident.md) | 5 | 0 |
| [resume](resume.md) | 5 | 0 |
| [returnstayplan](returnstayplan.md) | 3 | 0 |
| [role](role.md) | 13 | 0 |
| [safetycheck](safetycheck.md) | 5 | 0 |
| [schedule](schedule.md) | 24 | 0 |
| [school](school.md) | 10 | 0 |
| [scopefolder](scopefolder.md) | 2 | 0 |
| [seal](seal.md) | 3 | 0 |
| [search](search.md) | 2 | 0 |
| [securityincident](securityincident.md) | 1 | 0 |
| [service](service.md) | 8 | 0 |
| [shift](shift.md) | 11 | 0 |
| [shiftbudget](shiftbudget.md) | 5 | 0 |
| [signage](signage.md) | 5 | 0 |
| [skill](skill.md) | 3 | 0 |
| [social](social.md) | 11 | 0 |
| [succession](succession.md) | 6 | 0 |
| [supporter](supporter.md) | 2 | 0 |
| [survey](survey.md) | 6 | 0 |
| [sync](sync.md) | 1 | 0 |
| [team](team.md) | 10 | 0 |
| [template](template.md) | 8 | 0 |
| [ticket](ticket.md) | 4 | 0 |
| [timeline](timeline.md) | 12 | 0 |
| [timetable](timetable.md) | 13 | 0 |
| [todo](todo.md) | 9 | 0 |
| [tournament](tournament.md) | 31 | 0 |
| [translation](translation.md) | 3 | 0 |
| [user](user.md) | 1 | 0 |
| [venue](venue.md) | 1 | 0 |
| [village](village.md) | 40 | 0 |
| [visibility](visibility.md) | 2 | 0 |
| [weather](weather.md) | 4 | 0 |
| [webhook](webhook.md) | 5 | 0 |
| [workflow](workflow.md) | 8 | 0 |
| [_unmapped](_unmapped.md) | 9 | 5 |

- ドメイン数（`_unmapped` を除く）: 112
- テーブル合計: 830（`flyway_schema_history` を除く）
- ビュー合計: 5
- Entity 未紐付け（`_unmapped.md`）: テーブル 9 件 / ビュー 5 件
