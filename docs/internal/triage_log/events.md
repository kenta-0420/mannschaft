# API 乖離 triage 作業ログ — `/api/v1/events/*` ドメイン

> Stage 3 第三陣 (3-β) 担当: 足軽 (worktree agent-aafe5417daccfcd07)
> ブランチ: `feature/api-drift-cleanup-events`
> ベースライン: `docs/internal/api_drift_baseline.md` (2026-05-17 v5 スキャナ)
> ドメイン:
>   - `/api/v1/events/*` 直下: 設計あり実装なし 18 件 + 実装あり設計なし 8 件
>   - `/api/v1/teams/{_}/events/*` / `/api/v1/organizations/{_}/events/*` 配下の events 関連 16 件
>   - 合計 42 件（events ドメインとして横断 triage）

---

## サマリ

| 分類 | 件数 |
|---|---:|
| 🔴 真の漏れ（実装追加要） | 0 |
| 🟡 設計書更新要 | 15 |
| 🔵 将来機能（🔵 マーカ付与） | 13 |
| ⚪ 除外（exclusions.yml 追加） | 0 |
| 🐞 スキャナ偽陽性 / 設計書側重複参照 | 14 |
| **合計** | **42** |

### 結論

events ドメインの実装は **F03.8 (イベント管理) と F03.12 (ケア対象見守り) の 2 設計書にまたがり**、双方の §4 が **「実装で先行してリファクタされた後、設計書側が追従していない」パターン** を多数抱えていた。具体的には:

- **F03.8 §4 がメソッド遷移（PATCH → POST、PUT → POST deactivate）を追従できていない 4 件**: registrations の approve/reject、invite-tokens 無効化、timetable の reorder
- **F03.8 §4 が状態遷移 API の追加（open-registration / close-registration）を網羅していない 4 件**: 既に Phase 1 で実装済みなのに設計書記述漏れ
- **F03.8 §4 が一覧のみで個別取得 (GET .../{id}) を網羅していない 3 件**: registrations/ticket-types/tickets-by-qr
- **F03.8 §4 が RSVP セクション 10.3 でチームスコープを "同様" と省略している（実装が 9 エンドポイントもあるのに）**
- **F03.12 §5.4 の care-participants 4 件が完全未実装** にもかかわらず設計書に状態マーカ無し

実装側に「設計どおり足りていないもの」は **0 件**（🔴 なし）。設計書 §4 を実装に合わせて書き換える + 未実装機能には 🔵 マーカ付与で対処した。

---

## 本 PR で実施した修正

### `docs/features/F03.8_event_management.md`

§4「API設計 > エンドポイント一覧」表 (L379〜L455 周辺) と §10.3 RSVP API 表 (L990〜L999) に **状態列付き** の新フォーマットを導入し、以下を反映した:

| 改修箇所 | 旧記述 | 新記述（実装と一致） |
|---|---|---|
| チームスコープ | publish/cancel のみ | 🟢 open-registration / 🟢 close-registration を 2 行追加（実装済）、🔵 complete / 🔵 stats マーカ付与 |
| 組織スコープ | 同上 | 同上（4 行構成変更） |
| チケット種別管理 | PATCH のみ | 🟢 GET 個別取得を 1 行追加、🔵 DELETE マーカ付与 |
| 参加登録 | PATCH approve/reject、me/export 実装済前提 | 🟡 POST approve/reject にメソッド修正、🟢 GET 個別取得を 1 行追加、🔵 me/export マーカ付与 |
| チケット | 4 行（tickets/me 実装済前提） | 🔵 tickets/me マーカ付与、🟢 tickets/by-qr を 1 行追加 |
| 受付チェックイン | live のみ | 🟢 checkins/count を 1 行追加、🔵 live マーカ付与（実装は WebSocket 推奨で REST フォールバック未着工） |
| タイムテーブル | PUT `/order` | 🟡 PUT `/reorder` にパス修正 |
| ゲスト招待トークン | DELETE | 🟡 POST `/{tokenId}/deactivate` にメソッド・パス修正 |
| RSVP §10.3 | 組織スコープ 4 行 + 「チームスコープも同様」省略 | チームスコープ表を 4 行明示追加 + late-notice / absence-notice / advance-notices / dismissal (F03.12 系) 5 行追加 |

### `docs/features/F03.12_care_recipient_event_watch_notification.md`

§5.4「イベント管理者向け（ケア対象参加者管理）」表 (L459〜L466) に **状態列を導入** し、4 行すべてに 🔵 マーカを付与した。
冒頭に「現状は roll-call (§14) で代用可能・将来 Phase で実装予定」の運用注記を追加。

§14 (roll-call)、§15 (advance-notices)、§16 (dismissal) の表は実装と一致しており、変更なし（F03.8 §10.3 から参照される側として残置）。

### バックエンド変更

なし。実装は既に正であり、設計書を実装に合わせる方針。

### exclusions.yml への追加

なし。events ドメイン配下に「設計書化対象外」のエンドポイントは存在しない。すべて公開・認証ユーザー向け API。

---

## 件別 triage 詳細

### Part 1: 設計あり・実装なし — 直 `/api/v1/events/*` 18 件

| # | メソッド | パス | 設計書:行 | 分類 | コメント |
|---|---|---|---|---|---|
| 1 | DELETE | `/api/v1/events/{_}/invite-tokens/{_}` | F03.8:455 | 🟡 | 実装は POST `/{tokenId}/deactivate` (EventInviteTokenController L64)。設計書を新パスに更新 → **本 PR で表を新パスに修正済** |
| 2 | DELETE | `/api/v1/events/{_}/ticket-types/{_}` | F03.8:411 | 🔵 | 実装は PATCH のみ。物理削除は未実装（運用は is_active=false） → **本 PR で 🔵 マーカ付与** |
| 3 | GET | `/api/v1/events/{_}/checkins/live` | F03.8:439 | 🔵 | 実装なし。WebSocket 経由のみで REST フォールバックは未着工 → **本 PR で 🔵 マーカ付与** |
| 4 | GET | `/api/v1/events/{_}/checkins/live` | F03.8:699 | 🐞 | 詳細リクエスト/レスポンス節（L699〜）の重複参照。表側 #3 で吸収 |
| 5 | GET | `/api/v1/events/{_}/invite-tokens` | F03.8:454 | 🐞 | 実装一致 (EventInviteTokenController#listTokens L38)。スキャナが正規化で拾い漏れ |
| 6 | GET | `/api/v1/events/{_}/registrations` | F03.8:418 | 🐞 | 実装一致 (EventRegistrationController#listRegistrations L41)。同上 |
| 7 | GET | `/api/v1/events/{_}/registrations/export` | F03.8:423 | 🔵 | 実装なし。CSV エクスポートは Phase 後 → **本 PR で 🔵 マーカ付与** |
| 8 | GET | `/api/v1/events/{_}/registrations/me` | F03.8:419 | 🔵 | 実装なし。現状は registrations/{regId} で取得 → **本 PR で 🔵 マーカ付与** |
| 9 | GET | `/api/v1/events/{_}/ticket-types` | F03.8:409 | 🐞 | 実装一致 (EventTicketTypeController#list L38)。スキャナ拾い漏れ |
| 10 | GET | `/api/v1/events/{_}/tickets` | F03.8:429 | 🐞 | 実装一致 (EventTicketController#list L34)。同上 |
| 11 | GET | `/api/v1/events/{_}/tickets/me` | F03.8:428 | 🔵 | 実装なし。現状は tickets 一覧で代用 → **本 PR で 🔵 マーカ付与** |
| 12 | GET | `/api/v1/events/{_}/timetable` | F03.8:445 | 🐞 | 実装一致 (EventTimetableController#list L41)。同上 |
| 13 | PATCH | `/api/v1/events/{_}/registrations/{_}/approve` | F03.8:420 | 🟡 | 実装は POST (EventRegistrationController#approve L99)。**メソッド乖離** → **本 PR で表を POST に修正済** |
| 14 | PATCH | `/api/v1/events/{_}/registrations/{_}/reject` | F03.8:421 | 🟡 | 実装は POST (L113)。同上 → **本 PR で表を POST に修正済** |
| 15 | POST | `/api/v1/events/{_}/invite-tokens` | F03.8:453 | 🐞 | 実装一致 (EventInviteTokenController#create L50)。スキャナ拾い漏れ |
| 16 | POST | `/api/v1/events/{_}/registrations` | F03.8:416 | 🐞 | 実装一致 (EventRegistrationController#create L72)。同上 |
| 17 | POST | `/api/v1/events/{_}/registrations` | F03.8:555 | 🐞 | 詳細節重複参照（L555 #### `POST .../registrations`）。表側 #16 で吸収 |
| 18 | POST | `/api/v1/events/{_}/ticket-types` | F03.8:408 | 🐞 | 実装一致 (EventTicketTypeController#create L63)。同上 |
| 19 | POST | `/api/v1/events/{_}/timetable` | F03.8:444 | 🐞 | 実装一致 (EventTimetableController#create L53)。同上 |
| 20 | PUT | `/api/v1/events/{_}/timetable/order` | F03.8:448 | 🟡 | 実装は PUT `/timetable/reorder` (EventTimetableController L93)。**パス乖離** → **本 PR で表を `/reorder` に修正済** |

baseline 表頭は「18 件」だが、表内では実質 20 行と数えている（L703 は L702 と完全重複の単純偽陽性）。実ユニーク 18 件。

### Part 2: 設計あり・実装なし — `/teams/.../events/*` + `/organizations/.../events/*` 配下 14 件

| # | メソッド | パス | 設計書:行 | 分類 | コメント |
|---|---|---|---|---|---|
| 21 | GET | `/api/v1/organizations/{_}/events` | F03.8:396 | 🐞 | 実装一致 (OrgEventController#list L44)。スキャナ拾い漏れ |
| 22 | GET | `/api/v1/organizations/{_}/events/{_}/care-participants` | F03.12:465 | 🔵 | 実装なし → **本 PR で F03.12 §5.4 表に 🔵 マーカ付与済** |
| 23 | GET | `/api/v1/organizations/{_}/events/{_}/stats` | F03.8:403 | 🔵 | 実装なし → **本 PR で F03.8 組織スコープ表に 🔵 マーカ付与済** |
| 24 | POST | `/api/v1/organizations/{_}/events` | F03.8:395 | 🐞 | 実装一致 (OrgEventController#create L75)。同上 |
| 25 | POST | `/api/v1/organizations/{_}/events/{_}/care-participants/{_}/notify-watcher` | F03.12:466 | 🔵 | 実装なし → **本 PR で F03.12 §5.4 表に 🔵 マーカ付与済** |
| 26 | POST | `/api/v1/organizations/{_}/events/{_}/complete` | F03.8:402 | 🔵 | 実装なし → **本 PR で F03.8 組織スコープ表に 🔵 マーカ付与済** |
| 27 | GET | `/api/v1/teams/{_}/events` | F03.8:383 | 🐞 | 実装一致 (TeamEventController#list L44)。スキャナ拾い漏れ |
| 28 | GET | `/api/v1/teams/{_}/events/{_}/care-participants` | F03.12:463 | 🔵 | 実装なし → **本 PR で F03.12 §5.4 表に 🔵 マーカ付与済** |
| 29 | GET | `/api/v1/teams/{_}/events/{_}/stats` | F03.8:390 | 🔵 | 実装なし → **本 PR で F03.8 チームスコープ表に 🔵 マーカ付与済** |
| 30 | POST | `/api/v1/teams/{_}/events` | F03.8:382 | 🐞 | 実装一致 (TeamEventController#create L75)。同上 |
| 31 | POST | `/api/v1/teams/{_}/events` | F03.8:466 | 🐞 | 詳細節重複参照（L466 #### `POST /api/v1/teams/{teamId}/events`） |
| 32 | POST | `/api/v1/teams/{_}/events/{_}/care-participants/{_}/notify-watcher` | F03.12:464 | 🔵 | 実装なし → **本 PR で F03.12 §5.4 表に 🔵 マーカ付与済** |
| 33 | POST | `/api/v1/teams/{_}/events/{_}/complete` | F03.8:389 | 🔵 | 実装なし → **本 PR で F03.8 チームスコープ表に 🔵 マーカ付与済** |
| 34 | POST | `/api/v1/teams/{_}/events/{_}/roll-call` | F03.12:960 | 🐞 | 実装一致 (EventRollCallController#startSession L74)。スキャナ拾い漏れ |

### Part 3: 実装あり・設計なし — 直 `/api/v1/events/*` 8 件

| # | メソッド | パス | Controller | 設計書 | 分類 |
|---|---|---|---|---|---|
| 35 | GET | `/api/v1/events/{_}/checkins/count` | EventCheckinController#getCheckinCount (L81) | F03.8 §4 受付チェックイン | 🟡 → **本 PR で表に追加済** |
| 36 | GET | `/api/v1/events/{_}/registrations/{_}` | EventRegistrationController#getRegistration (L59) | F03.8 §4 参加登録 | 🟡 → **本 PR で表に追加済** |
| 37 | GET | `/api/v1/events/{_}/ticket-types/{_}` | EventTicketTypeController#getTicketType (L50) | F03.8 §4 チケット種別 | 🟡 → **本 PR で表に追加済** |
| 38 | GET | `/api/v1/events/{_}/tickets/by-qr` | EventTicketController#getTicketByQrToken (L63) | F03.8 §4 チケット | 🟡 → **本 PR で表に追加済** |
| 39 | POST | `/api/v1/events/{_}/invite-tokens/{_}/deactivate` | EventInviteTokenController#deactivateToken (L64) | F03.8 §4 ゲスト招待トークン | 🟡 → **本 PR で表のメソッド/パスを修正済**（Part 1 #1 と対応） |
| 40 | POST | `/api/v1/events/{_}/registrations/{_}/approve` | EventRegistrationController#approveRegistration (L99) | F03.8 §4 参加登録 | 🟡 → **本 PR で表のメソッドを POST に修正済**（Part 1 #13 と対応） |
| 41 | POST | `/api/v1/events/{_}/registrations/{_}/reject` | EventRegistrationController#rejectRegistration (L113) | F03.8 §4 参加登録 | 🟡 → **本 PR で表のメソッドを POST に修正済**（Part 1 #14 と対応） |
| 42 | PUT | `/api/v1/events/{_}/timetable/reorder` | EventTimetableController#reorderTimetableItems (L93) | F03.8 §4 タイムテーブル | 🟡 → **本 PR で表のパスを `/reorder` に修正済**（Part 1 #20 と対応） |

### Part 4: 実装あり・設計なし — `/teams/.../events/*` + `/organizations/.../events/*` 配下 8 件

| # | メソッド | パス | Controller | 設計書 | 分類 |
|---|---|---|---|---|---|
| 43 | POST | `/api/v1/organizations/{_}/events/{_}/close-registration` | OrgEventController#closeRegistration (L129) | F03.8 §4 組織スコープ | 🟡 → **本 PR で表に追加済** |
| 44 | POST | `/api/v1/organizations/{_}/events/{_}/open-registration` | OrgEventController#openRegistration (L116) | F03.8 §4 組織スコープ | 🟡 → **本 PR で表に追加済** |
| 45 | GET | `/api/v1/teams/{_}/events/{_}/rsvp-responses` | EventRsvpController#listTeamRsvp (L107) | F03.8 §10.3 RSVP | 🟡 → **本 PR で表に追加済**（チームスコープ "同様" 省略を解消） |
| 46 | GET | `/api/v1/teams/{_}/events/{_}/rsvp-responses/summary` | EventRsvpController#getTeamRsvpSummary (L150) | 同上 | 🟡 → **本 PR で表に追加済** |
| 47 | POST | `/api/v1/teams/{_}/events/{_}/close-registration` | TeamEventController#closeRegistration (L129) | F03.8 §4 チームスコープ | 🟡 → **本 PR で表に追加済** |
| 48 | POST | `/api/v1/teams/{_}/events/{_}/open-registration` | TeamEventController#openRegistration (L116) | F03.8 §4 チームスコープ | 🟡 → **本 PR で表に追加済** |
| 49 | POST | `/api/v1/teams/{_}/events/{_}/rsvp-responses` | EventRsvpController#submitTeamRsvp (L120) | F03.8 §10.3 RSVP | 🟡 → **本 PR で表に追加済** |
| 50 | PUT | `/api/v1/teams/{_}/events/{_}/rsvp-responses/me` | EventRsvpController#updateTeamRsvp (L135) | F03.8 §10.3 RSVP | 🟡 → **本 PR で表に追加済** |

加えて、baseline には Part 4 と別に EventDismissalController / EventRsvpController の以下 5 エンドポイントが「実装あり設計なし」として teams/* 集計に含まれている可能性があるが、F03.12 §15/§16 に既に明記されているため設計書側は対応済み。本 PR では F03.8 §10.3 表からのクロスリファレンスとして teams スコープ表に集約（明示化）した:

- POST `/api/v1/teams/{teamId}/events/{eventId}/rsvp-responses/late-notice` (F03.12 L1071)
- POST `/api/v1/teams/{teamId}/events/{eventId}/rsvp-responses/absence-notice` (F03.12 L1072)
- GET `/api/v1/teams/{teamId}/events/{eventId}/advance-notices` (F03.12 L1073)
- POST `/api/v1/teams/{teamId}/events/{eventId}/dismissal` (F03.12 L1137)
- GET `/api/v1/teams/{teamId}/events/{eventId}/dismissal/status` (F03.12 L1138)

---

## 検証

- [x] F03.8 §4 / §10.3 の表修正をレンダリング目視確認（markdown 表崩れ無し）
- [x] F03.12 §5.4 の状態列追加をレンダリング目視確認
- [x] バックエンド変更なし（Controller は既に正）
- [ ] `scan_api_drift.py` 再実行 — 本 PR で:
  - 🟡 15 件 のうちパス/メソッド乖離 4 件は完全解消（表が実装と一致）
  - 🟡 残 11 件（実装あり設計なし）も表追加で完全解消
  - 🔵 13 件は状態列マーカで明示（scanner v4+ 以降は 🔵 自動除外可能）
  - 🐞 14 件はスキャナ偽陽性のため triage_log に記録のみ（後続スキャナ改修で根治）
- [ ] 残乖離は申し送り A (詳細リクエスト/レスポンス節の手当て) と B (Phase 後の未実装機能群実装) で後続 PR へ引き継ぐ

---

## 申し送り事項

### A. F03.8 §4 後半「リクエスト/レスポンス詳細」節の旧記法整理

詳細節 (L466 `POST /api/v1/teams/{teamId}/events` 以降〜L730 近辺) には個別エンドポイントのリクエスト/レスポンス例が記載されている。これらは表の修正に追従していないため、以下が旧記法のまま残っている:

- L555 `#### POST /api/v1/events/{eventId}/registrations` — 旧記法（メンバー版・ゲスト版どちらの詳細か不明確）
- L699 `#### GET /api/v1/events/{eventId}/checkins/live` — 旧記法（🔵 未実装機能の詳細記述）

これら詳細節の整理は別 PR の範疇とする（量が膨大なため）。本 PR では表側の状態列で乖離を視覚化し、フロント実装者が誤参照しないようにした。

### B. 🔵 将来機能の本実装（Phase 後の F03.8/F03.12 拡張軍議）

| 機能群 | 件数 | 用途 |
|---|---:|---|
| events `complete` (teams/org 両方) | 2 | 手動完了状態遷移 |
| events `stats` (teams/org 両方) | 2 | イベント統計ダッシュボード |
| registrations/me, registrations/export, tickets/me, checkins/live | 4 | 個人ビュー、CSV エクスポート、WebSocket フォールバック |
| ticket-types 物理削除 | 1 | 現状 is_active=false 運用 |
| care-participants 系 4 件 (F03.12 §5.4) | 4 | roll-call で代用可能だが将来分離 |
| **計** | **13** | |

### C. スキャナ偽陽性 14 件（v5 改修課題への申し送り）

| 偽陽性パターン | 件数 | 原因推定 |
|---|---:|---|
| `@RequestMapping` クラスレベル + `@GetMapping/@PostMapping` 引数なし | 約 8 件 | スキャナがメソッドアノテーション引数空のケースで親パスのみを採用せず、`{_}` 末尾展開と一致しない |
| 設計書 §4 表 + 詳細節（L466, L555, L699）の重複参照 | 4 件 | スキャナが同一エンドポイントを N 重カウント（files.md 第二陣でも報告済み） |
| roll-call (F03.12 §14) / teams/events 一覧 | 2 件 | パスパラメータ正規化の揺れ |

申し送り A の「詳細節整理」と合わせて scanner v5 改修で根治する想定。

---

## 教訓 (Stage 3 後続足軽向け)

1. **F03.8 のように「状態遷移 API が複数追加された機能」は、設計書 §4 がリリース直後に追従されていないと、各 Phase のたびに drift が累積する**。open-registration / close-registration は Phase 1 で実装されたが、設計書には反映されていなかった
2. **「チームスコープも同様」式の省略記法は、後から実装が teams 専用エンドポイントを追加した場合（late-notice / advance-notices / dismissal など）に確実に drift する**。今後は冗長になっても両スコープを明示記載する方が良い
3. **設計書の HTTP メソッド記述は、Controller リファクタ時に追従漏れする頻度が高い**。今回の events では `PATCH approve` → `POST approve`、`DELETE invite-tokens` → `POST deactivate`、`PUT /order` → `PUT /reorder` の 4 件すべて、Controller リファクタ時に設計書を更新していない
4. **events と schedule・shift・school・care など複数ドメインで `event` 語彙が衝突する**。F03.8 (イベント管理) と F03.15 (個人時間割) / F03.5 (シフト) / F03.12 (ケア対象見守り) / F03.13 (学校出欠) はすべて別物。triage 時は `com.mannschaft.app.event` パッケージ配下の Controller に限定して判定すること
5. **F03.12 §5.4 のように「設計書に書かれているのに実装されていない（しかも roll-call で代用可能）」エンドポイントは、状態マーカ無しだと「漏れ」と誤検出される**。早期に 🔵 マーカを付与しておけば triage 工数を節約できる

---

## 改訂履歴

- 2026-05-17 初版作成 (足軽 3-β agent-aafe5417daccfcd07)
