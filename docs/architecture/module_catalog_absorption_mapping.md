# モジュールカタログ 吸収マッピング台帳（Wave3）

## 1. 概要

モジュールカタログ（`module_definitions` テーブル・番号 #1-70）は以下の波状展開で登録を進めてきた。

| Wave | Migration | 内容 |
|---|---|---|
| Wave1 | `V155.20260717114354__seed_module_definitions_wave1.sql` | 8モジュール新規登録（tournament/event/budget/school_attendance/timetable/recruitment/committee/form） |
| Wave2 | `V156.20260718070942__seed_module_definitions_wave2.sql` | 13モジュール新規登録（job_matching/promotion/point_card/blog_cms/memo/contact/social/reflection/family/succession/market/workflow/ticket） |
| **Wave3** | `V157.20260718083724__amend_module_descriptions_wave3.sql` | **新規seed行なし。** B群ドメイン（独自モジュールを持たない）の吸収先整理と、既存6モジュールの description 追補 |

Wave1/Wave2 で #1-70 のモジュール番号は一通り確保・完結している。Wave3 は新しい番号を採らず、以下の2種類を整理・記録する:

1. **独自モジュールを持たず既存モジュールに吸収される B群ドメイン**（実装パッケージは存在するが、カタログ上は別モジュールの機能として扱われるもの）
2. **カタログ対象外の横断インフラ・外部連携ドメイン**（module_definitions に登録しない）

いずれも DB 変更を伴わない整理作業だが、実装パッケージとカタログ上のモジュールの対応関係を明文化しておくことで、将来の探索・監査（認可監査・機能フラグ監査等）での取り違えを防ぐ。

## 2. B群 吸収マッピング表

| 吸収元ドメイン（実装パッケージ） | 吸収先モジュール（番号） | 実装パッケージ | 備考 |
|---|---|---|---|
| chart | medical_record（#39） | `backend/src/main/java/com/mannschaft/app/chart/*` | カルテの実体は chart パッケージ |
| facility | reservation（#31） | `app/facility/*` | 施設予約 |
| receipt | payment（#30） | `app/receipt/*` | 領収書発行 |
| proxyvote | voting（#36） | `app/proxyvote/*` | 代理投票・委任状 |
| residencestatus | safety_check（#23）＋resident_register（#37） | `app/residencestatus/*` | 安否確認と住民活動集約に跨る |
| skill | member_intro（#20） | `app/skill/*` | スキル紹介・スキルマトリクス |
| faq | knowledge_base（#41） | `app/faq/*` | |
| digest | timeline（#3） | `app/digest/*` | テーブル `timeline_digest*` |
| supporter | social（#63） | `app/supporter/*` ＋ `app/social/FollowController` | フォロー機構。social は Wave2 で #63 登録済 |

### 重要注記

voting（#36）は専用 `voting` パッケージを持たず、実装は `app/proxyvote/*` と survey が担う。台帳上のモジュールは実在するが、実装実体の所在（`proxyvote`）を取り違えないよう注意すること。

## 3. 独立モジュール化した B群3件（Wave1/Wave2で独自登録済）

以下3件は吸収されず、独自のモジュール番号を確保して個別登録済み:

- `form`（#68・Wave1）
- `workflow`（#69・Wave2）
- `ticket`（#70・Wave2）

## 4. カタログ対象外（横断インフラ・外部連携）

以下のドメインは特定の村・チームに紐づく「モジュール」ではなく、他ドメインからも横断的に利用される基盤・外部連携であるため `module_definitions` には登録しない:

| ドメイン | 実装パッケージ | 備考 |
|---|---|---|
| venue | `app/venue/*` | Google Places 会場マスタ供給 |
| weather | `app/weather/*` | 気象データ連携・Geonames取込 |
| line | `app/line/*` | LINE Bot/Webhook。他ドメインからも横断利用 |

## 5. Wave3 description 追補の記録

`V157.20260718083724__amend_module_descriptions_wave3.sql` で以下6モジュールの description に追記した内容:

| モジュール（slug） | 追記文 | 吸収元 |
|---|---|---|
| timeline | （ダイジェスト生成を含む） | digest |
| member_intro | （スキル紹介を含む） | skill |
| payment | （領収書発行を含む） | receipt |
| voting | （代理投票・委任状を含む） | proxyvote |
| safety_check | ・平時の居住状況確認 | residencestatus |
| knowledge_base | ・FAQ | faq |

いずれも `CONCAT(description, '...')` による追記＋`NOT LIKE` ガードで冪等化している（二重適用されても増殖しない）。新規 seed 行は追加していない。

## 6. 契約テスト

`backend/src/test/java/com/mannschaft/app/template/ModuleCatalogWave3DescriptionIT.java` が、V157 適用後に上記6モジュールの description が期待する追記トークンを含むことを検証する（Testcontainers + Flyway 直接適用方式。Wave2 の `ModuleCatalogWave2SeedIT` と同じ金型）。
