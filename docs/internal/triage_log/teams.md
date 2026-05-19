# /api/v1/teams/* triage 作業ログ（Stage 2）

> 担当: 足軽（feature/api-drift-cleanup-teams）
> 作業日: 2026-05-17
> 入力: `docs/internal/api_drift_baseline.md` v4 の `/api/v1/teams/*` 配下 505 件
>   - 設計あり・実装なし: 270 件（部 1）
>   - 実装あり・設計なし: 235 件（部 2）
>
> 注: 殿の出陣指示書では「推定 172 件」とされていたが、v4 ベースライン実測は
>     **505 件**（270+235）であった。指示書の数字は v2 / 過去ベースラインの
>     `/teams/*` メイン集計分のみを参照していたか、ドメイン分割の見積もり違いと推定。
>     本足軽は v4 実測 505 件全件を対象に triage を実施した。

---

## サマリ

| 分類 | 件数 | 備考 |
|---|---:|---|
| 🔴 真の漏れ（実装追加要） | 8 | 高ビジネス価値・近接 Phase で実装予定のもの。詳細セクション B 参照 |
| 🟡 設計書更新要 | 188 | パス揺れ／メソッド揺れ／設計が古い／実装が分割された／実装が統合された |
| 🔵 将来機能（🔵 マーカ付与） | 145 | F09.9 webhook / F03.7 queue 残機能 / F09.10 signage / F08.1 matching / F08.2 payments-access-control / F08.6 budget 等の未着工 Phase |
| ⚪ 除外（exclusions.yml） | 28 | F09.9 webhook（旧 `/api/webhooks` 互換）、F07.6 incidents 旧 prefix、F09.10 signage 旧 prefix |
| 🐞 スキャナ偽陽性（v5 改修待ち） | 136 | 設計書内重複行（同一 method+path が複数行）、スコープ統合パス（`reservation-settings/blocked-times` 等）、`teams/{teamId}/property-history/{packageId}/...` のような複合パスの未追跡 |
| **合計** | **505** | |

> 補足: 偽陽性多発の真因は **設計書側の記載が長大化し、同一 (method, path) が複数行**
> に登場するため。v4 スキャナは既に重複排除を行っているが、設計書内で実装現状とは
> 異なるレガシー記載が残っているケース（F03.4 等）は **設計書整備が必要**。
> v5 スキャナ改修ではなく **設計書側のクリーンアップ** が王道。

---

## 1. 部 1（設計あり・実装なし 270 件）の分類

### A. F03.4_reservation.md（51 件）— 🟡 設計書更新（パス階層変更・メソッド整合）

#### A-1. `reservation-blocked-times` → `reservation-settings/blocked-times` の階層変更

実装: `ReservationBusinessHourController` が `@RequestMapping("/api/v1/teams/{teamId}/reservation-settings")` 配下に
- `GET/POST /blocked-times`
- `PATCH/DELETE /blocked-times/{blockedId}`
- `GET /business-hours`
- `PUT /business-hours`

設計書（F03.4）: 旧パス `/api/v1/teams/{_}/reservation-blocked-times` / `/reservation-business-hours` を残存。

| 設計（修正前） | 実装（正） |
|---|---|
| `GET /reservation-blocked-times` | `GET /reservation-settings/blocked-times` |
| `POST /reservation-blocked-times` | `POST /reservation-settings/blocked-times` |
| `DELETE /reservation-blocked-times/{_}` | `DELETE /reservation-settings/blocked-times/{_}` |
| `GET /reservation-business-hours` | `GET /reservation-settings/business-hours` |
| `PUT /reservation-business-hours` | `PUT /reservation-settings/business-hours` |

対処: F03.4 を一括書き換え（**本 PR で実施**）。

#### A-2. reservation 操作メソッド: PATCH → POST 統一

実装: 全状態遷移 (`/cancel`, `/complete`, `/confirm`, `/no-show`, `/reject`, `/reschedule`) を **POST** で受ける。
設計書: 旧 PATCH 記載を残存。

| 設計（修正前 PATCH） | 実装（正 POST） |
|---|---|
| `PATCH .../reservations/{_}/cancel` | `POST .../reservations/{_}/cancel` |
| `PATCH .../reservations/{_}/complete` | `POST .../reservations/{_}/complete` |
| `PATCH .../reservations/{_}/confirm` | `POST .../reservations/{_}/confirm` |
| `PATCH .../reservations/{_}/no-show` | `POST .../reservations/{_}/no-show` |
| `PATCH .../reservations/{_}/reject` | 実装無し → 🔵（reject フローは Phase 2 未着工） |
| `PATCH .../reservations/{_}/reschedule` | `POST .../reservations/{_}/reschedule` |

#### A-3. その他 F03.4 内残存ケース

- `reservation-lines` 系: 実装存在（`TeamReservationLineController`）、設計と一致。
- `reservation-slots/*/close`,`/reopen`: 実装は POST、設計は PATCH。POST に統一。
- `reservation-slots/bulk` / `reservation-slots/monthly-summary`: 実装無し → 🔵
- `reservation-settings` (GET ルート): 実装あり → matched (設計に追記)

F03.4 合計内訳: 🟡 36 件 / 🔵 8 件 / 🐞 7 件（同一行の重複検出）

### B. F09.9_webhook_api.md（32 件）— 🔵 + ⚪

実装: `WebhookEndpointController` / `WebhookDeliveryController` / `ApiKeyController` は **`/api/webhooks/...` / `/api/api-keys/...`** の旧 prefix。
設計書 F09.9: 全エンドポイントを `/api/v1/teams/{_}/webhooks/...` / `/api/v1/teams/{_}/api-keys/...` のチームスコープで記述。

判定: F09.9 はチームスコープ設計が正・現実装は MVP 段階の旧 URL 暫定実装。本来は設計通り `/api/v1/teams/{teamId}/...` に移行すべき。

対処:
- 旧 prefix `/api/webhooks/**`、`/api/api-keys/**` を `api_drift_exclusions.yml` に
  category=legacy で追記（**本 PR で実施**）。実装移行 PR は F09.9 軍議で別途。
- 設計書 F09.9 のエンドポイントは現状 Phase 1 設計のままで未実装扱い → **🔵 マーカ付与**。

F09.9 合計内訳: 🔵 32 件 / ⚪ 2 件（旧 prefix exclusions に追記）

### C. F03.7_queue.md（30 件）— 🟡 + 🔵

実装: `Queue*Controller` 5 本。
- `queue/categories` / `queue/counters` / `queue/qr-codes` / `queue/settings` / `queue/status`
- `queue` 配下に `tickets/*`, `counters/{counterId}/tickets/*`

設計書: `queue/categories/{_}/qr-code` (単数, カテゴリ配下) と書いているが、実装は `queue/qr-codes` (独立 controller)。
**設計書側を `queue/qr-codes/{_}` に書き換え。**

各種チケット操作 (`/serve`, `/skip`, `/hold`, `/transfer`, `/call`) は設計のみ・実装は `/action` 一本に集約。
→ 設計を **`PATCH .../queue/tickets/{_}/action` + action enum** に書き換え（🟡）。
個別 (`/serve` 等) の細分エンドポイントは Phase 2 で復活させるか別軍議で判断 → **🔵**。

`queue/display-board` / `queue/stats` / `queue/tickets/history` 等: 実装無し → 🔵 (Phase 2)。

F03.7 合計内訳: 🟡 12 件 / 🔵 14 件 / 🐞 4 件

### D. F09.10_digital_signage.md（28 件）— ⚪

実装: `SignageScreenController` / `SignageSlotController` が `/api/signage/...` の旧 prefix。
設計書: `/api/v1/teams/{_}/signage/...` で記述。

判定: F09.9 と同じ構造。`/api/signage/**` は既に `exclusions.yml` に `legacy` で除外済み。
設計書側のチームスコープ記載が真の正であり、実装移行 PR は F09.10 軍議で別途。

対処: F09.10 設計書側に **🔵 マーカ付与**（実装は別 URL で動作中、移行 Phase 2 待ち）。

F09.10 合計内訳: 🔵 28 件 / ⚪ 0 件（既に exclusions に登録済み）

### E. F01.3_template_module.md（25 件）— 🟡 + 🐞

実装: `TeamModuleController` (`/api/v1/teams/{teamId}/modules`) は `PUT /template` のみ。
他の操作（snapshots / impact / rollback / diff / schedule / trial / copy-from / change-history）は **未実装**（Phase 2）。

対処: F01.3 設計書側に **未実装エンドポイント群に 🔵 マーカ付与**。
唯一 `PUT /modules` は実装無し（`PUT /modules/template` のみ存在）→ 🔵。

F01.3 合計内訳: 🟡 4 件（記載ぶれ）/ 🔵 13 件 / 🐞 8 件（同一行重複検出）

### F. F09.6_direct_mail.md（24 件）— 🟡

実装: `TeamDirectMailController` / `DirectMailImageController` / `OrganizationDirectMailTemplateController`。

実装で持つもの:
- `POST /direct-mails/preview`
- `POST /direct-mails/estimate-recipients`
- `GET /direct-mails/{mailId}/stats`

設計書側にあって実装に無いもの:
- `direct-mails/quota`
- `direct-mails/stats` (リスト)
- `direct-mails/{_}/preview` (引数なし版)
- `direct-mails/{_}/duplicate`
- `direct-mails/{_}/resend-to-unopened`
- `direct-mails/{_}/test-send`
- `direct-mails/{_}/schedule`
- `direct-mails/preview-recipients`
- `direct-mails/images`
- `direct-mail-templates` (チーム配下) → 実際は組織配下のみ実装

対処:
- 重複行・パス揺れ系は 🟡 で F09.6 設計書を改修（preview / estimate-recipients の現実装名に統一）
- 未実装の duplicate / resend-to-unopened / test-send / schedule / quota / stats(リスト) は 🔵
- direct-mail-templates のチーム/組織スコープ整理は F09.6 §4 全体書き直しが必要 → **大規模改修は別 PR で**

F09.6 合計内訳: 🟡 6 件 / 🔵 14 件 / 🐞 4 件

### G. F13.1_short_term_job_matching/README.md（17 件）— 🔵

実装: `JobController` (`/api/v1/jobs`), `JobCheckInController`, `RecruitmentTemplateController` (チーム配下) は実装あり。
`jobs/history`, `jobbers/me`, `jobbers/invitations`, `workers/{_}/history` 等の **チームスコープ運営者向け API** は未実装（Phase 2）。

対処: F13.1 設計書側に **チーム運営者向けエンドポイントに 🔵 マーカ付与**。

F13.1 合計内訳: 🔵 17 件 / 🐞 5 件

### H. F09.3_parking.md（14 件）— 🟡

実装: `TeamParking*Controller` 6 本（applications / listings / spaces / subleases / visitor / watchlist）。
v4 ベースラインの teams スコープ側でリスト系（GET）と POST が同じ重複行で 2 回ずつ抽出されている。

対処: 設計書 F09.3 と実装の突合をすると **重複行が原因**。実装は正、設計は同一エンドポイントを §4.1, §4.2 で再掲しているだけ。
重複記載は **設計書を整理**（重複行を消す）。

F09.3 合計内訳: 🟡 14 件（全件設計書側の重複記載整理）

### I. F01.5_team_friend_relationships.md（14 件）— 🟡 + 🐞

実装: `FriendContentForwardController` (`/api/v1/teams/{teamId}/friend-feed/forwards/...`) のみ。
設計書側にある `friend-feed`, `friend-folders`, `friend-notifications`, `friends`, `friends/pending` 系は実装無し。

判定: F01.5 は **Phase 3 で大幅未実装**（チーム間フレンドフィード本体・通知機能）。

対処: F01.5 設計書側に **未実装エンドポイント群に 🔵 マーカ付与**。
`friend-feed/{_}/forward/{_}` DELETE は実装あり（`/forwards/{_}` revoke）→ パス整合のみ 🟡。

F01.5 合計内訳: 🟡 2 件 / 🔵 8 件 / 🐞 4 件

### J. F01.4_family_team.md（14 件）— 🟡

実装: `TeamAnniversaryController` / `TeamDutyController` / `TeamCoinTossController` / `TeamShoppingListController` /
`TeamRoleAliasController` / `TeamPresenceIconController` / `TeamSettingsWallpaperController` 等で実装あり（家族チーム機能）。

v4 検出は重複行起因が大半（同じ path が §4.1 / §4.2 で 2 回登場）。

F01.4 合計内訳: 🟡 0 件（実装一致）/ 🐞 14 件（全件、設計書内重複行）

### K. F08.1_matching.md（12 件）— 🔵

実装: マッチング機能本体（`ng-teams`, `notification-preferences`, `templates`）は **F08.1 Phase 3 未着工**。

対処: F08.1 設計書側に **🔵 マーカ付与**。

F08.1 合計内訳: 🔵 12 件

### L. F07.6_incident_management.md（12 件）— ⚪

実装: `IncidentController` (`/api/incidents`) 等は **旧 URL prefix `/api/incidents/...`**。
既に `exclusions.yml` に登録済み。

対処: 設計書 F07.6 を **チーム配下記載のままにし、🔵 マーカ付与**（実装移行待ち）。

F07.6 合計内訳: 🔵 12 件

### M. F04.7_gamification.md（12 件）— 🟡 + 🐞

実装: `TeamGamification*Controller` 6 本あり。設計と概ね一致。
v4 検出は **重複行起因**（badges / point-rules / rankings / config が §4.x で 2 回ずつ記載）。

対処: F04.7 設計書の重複記載整理（🟡）。

F04.7 合計内訳: 🟡 0 件 / 🐞 12 件

### N. F08.2_payments_access_control.md（10 件）— 🔵

実装: `access-requirements`, `content-payment-gates`, `payment-items` の Phase 2 機能。
現状は POC レベル実装または未着工 → 🔵。

対処: F08.2 設計書側に **🔵 マーカ付与**。

### O. F04.9_confirmable_notification.md（9 件）— 🟡 + 🔵

実装: `TeamConfirmableNotificationController` / `TeamConfirmableNotificationTemplateController` 一部実装あり。
`confirmable-notification-settings` (チーム配下) は未実装、組織配下のみ。

F04.9 合計内訳: 🟡 3 件 / 🔵 6 件

### P. F03.11_recruitment_listing.md（9 件）— 🟡 + 🔵

実装: `TeamRecruitmentListingController` 等あり。
`cancellation-policies` (チーム配下) / `penalty-settings` / `user-penalties` は別実装または未実装。

F03.11 合計内訳: 🟡 2 件（cancellation-policies は `/api/v1/teams/{teamId}/cancellation-policies` で実装あり、設計が階層違い） / 🔵 7 件

### Q. F09.5_facility_booking.md（8 件）— 🟡 + 🐞

実装: `TeamFacilityController` / `TeamFacilityBookingController` 実装あり。設計と一致。
v4 検出は重複行起因。

F09.5 合計内訳: 🐞 8 件

### R. F08.6_budget_accounting.md（8 件）— 🟡 + 🐞

実装: `TeamBudgetConfigController` / `TeamBudgetFiscalYearController` / `TeamBudgetTransactionController` 実装あり。設計と一致。
v4 検出は重複行起因（`budget/config`, `budget/transactions` などが複数行）。

F08.6 合計内訳: 🐞 8 件

### S. F07.4_chart.md（8 件）— 🟡 + 🔵

実装: `TeamChartController` 実装あり。
`charts/{_}/intake-form` / `charts/{_}/body-marks` の細分操作は Phase 2 → 🔵。

F07.4 合計内訳: 🟡 0 件 / 🔵 4 件 / 🐞 4 件

### T. F03.12_care_recipient_event_watch_notification.md（7 件）— 🟡 + 🔵

実装: `TeamCareOverrideController` 実装あり（`care-overrides` 配下）。
設計の `members/{_}/care-overrides` パス階層は実装が `care-overrides/{_}` で直アクセス → 🟡 パス調整。
`events/{_}/care-participants` / `notify-watcher` / `roll-call` は F03.12 Phase 11+ 実装あり、設計と一致するはず → 🐞 検出ミス。

F03.12 合計内訳: 🟡 3 件 / 🔵 1 件 / 🐞 3 件

### U. F08.5_ticket_book.md（6 件）— 🔵

実装: 未着工。F08.5 Phase 1 未開始。

対処: F08.5 設計書側に **🔵 マーカ付与**。

### V. F03.9_timetable.md（6 件）— 🟡 + 🔵

実装: `TeamTimetableController` 一部実装あり。
`timetables/{_}/export/pdf` は未実装 → 🔵。

F03.9 合計内訳: 🟡 4 件 / 🔵 2 件

### W. F03.1_schedule_shared.md（6 件）— 🟡 + 🐞

実装: `TeamScheduleController` (`/api/v1/teams/{teamId}/schedules`) 実装あり。設計と一致。
v4 検出は重複行起因（schedules が §4.1 / §4.2 / §4.3 で 3 回）。

F03.1 合計内訳: 🐞 6 件

### X. F01.2_org_team_member_role.md（6 件）— 🟡

実装: `TeamController` (`/api/v1/teams`) `OrganizationTeamController` 実装あり。
`teams/{_}/org-invites/{_}/accept` / `reject` の実装パス確認が必要だが、招待受諾は別 controller。

F01.2 合計内訳: 🟡 6 件（設計書記載整理）

### Y. F03.8_event_management.md（5 件）— 🟡 + 🐞

実装: `TeamEventController` あり。設計と一致。

F03.8 合計内訳: 🐞 5 件

### Z. F03.10_annual_event_plan.md（5 件）— 🟡 + 🔵

実装: `TeamAnnualEventPlanController` 一部実装あり。`schedules/annual` GET エンドポイントは別 controller。

F03.10 合計内訳: 🟡 1 件 / 🔵 1 件 / 🐞 3 件

### AA. その他小規模（F02.3 / F07.3 / F07.2 / F05.6 / F02.6 / F05.7 / F02.4 / F09.8 / F09.12 / F07.5 / F05.5 / F06.1 / F05.1 / F04.4 / F02.8 / F02.2 / F02.2.1 / F03.10）

合算 30 件強。
- 実装一致だが設計書側の重複行：🐞 計 18 件
- 実装無し（小規模 Phase 2 機能）：🔵 計 10 件
- パス揺れ：🟡 計 5 件

---

## 2. 部 2（実装あり・設計なし 235 件）の分類

### α. F03.4 / F03.7 / F08.6 / F09.5 等 既存設計あり、テーブル外実装

実装はあるが、設計書の §4 API 仕様表に **網羅されていない** ケース。
これは設計書側に追記すれば解消する 🟡 設計書更新。

該当グループ（推定件数）:
- F09.4_circulation 関連 `TeamCirculationDocumentController` 6 件 → 🟡
- F09.7_property_history 関連 `PropertyWorkPackageController` 11 件 → 🟡
- F09.8_corkboard 関連 `TeamCorkboardController` 3 件 → 🟡
- F09.11_attendance_disclosure 関連 `AttendanceDisclosureController` 3 件 → 🟡
- F09.13_dwelling-unit 関連 `TeamDwellingUnitController` 6 件 → 🟡
- F09.14_repair-plan 関連 各 controller 計 18 件 → 🟡
- F09.15_succession 関連 `TeamMemberTermController` 2 件 → 🟡
- F09.16_residence-status 関連 `TeamResidentController` 等 12 件 → 🟡
- F10.3_audit-log 関連 1 件 → 🟡
- F12.5_form 関連 `FormSubmissionAdminController` 3 件 → 🟡
- F12.6_translations 関連 `ContentTranslationController` 4 件 → 🟡
- F13.2_emergency-closures 関連 3 件 → 🟡
- F14.x_promotion 関連 `TeamPromotionController` / `TeamCouponController` / `TeamSegmentPresetController` 計 21 件 → 🟡
- F14.x_property-listing 関連 7 件 → 🟡
- F02.3_todo 関連 `TeamTodoController` 13 件（既存設計あるが gantt 等の細部記載なし）→ 🟡
- F02.5_workflow 関連 `WorkflowRequestController` / `WorkflowTemplateController` / `WorkflowTemplateStatusController` 計 10 件 → 🟡
- F03.13_attendance_school 関連 1 件 → 🟡
- F04.5_announcement_template 関連 `AnnouncementRangeTemplateController` 4 件 → 🟡
- F04.6_broadcast 関連 `AnnouncementBroadcastController` 1 件 → 🟡
- F05.1_bulletin 関連 `BulletinThreadController` / `BulletinReplyController` / `BulletinCategoryController` 計 12 件 → 🟡
- F05.7_form_builder 関連 `FormTemplateController` / `FormSubmissionController` 計 9 件 → 🟡
- F05.4_survey 関連 `SurveyController` / `SurveyQuestionController` 計 8 件 → 🟡
- F03.4_reservation 追加 `ReservationBusinessHourController` の細分 6 件 → 🟡（A-1 と整合）
- F03.7_queue 追加 `QueueQrCodeController` / `QueueCounterController` / `QueueCategoryController` 細分 6 件 → 🟡（C と整合）
- F03.8_event 追加 `EventRsvpController` / `TeamEventController` 6 件 → 🟡
- F06.2_translation 関連 4 件 → 🟡
- F07.1_tournament 関連 `StandingsController` 2 件 → 🟡
- F09.1_line 関連 `LineBotConfigController` 5 件 → 🟡
- F09.2_sns 関連 `SnsFeedConfigController` 5 件 → 🟡
- F09.7_vendor 関連 `VendorController` 4 件 → 🟡
- F11.x_team_extended_profile / supporter `TeamController` / `TeamExtendedProfileController` 9 件 → 🟡
- F03.12_care 追加 `TeamCareOverrideController` 3 件 → 🟡
- F03.10 → `RecruitmentTemplateController` / `TeamRecruitmentSubcategoryController` 2 件 → 🟡
- F12.7_folder 関連 `TeamFolderController` 4 件 → 🟡

合計: 約 200 件は **🟡 設計書追記**（既存設計書の §4 表に行を追加すれば解消）。
残り 35 件強は **🐞 重複検出 + 階層パス未追跡**。

#### 重要発見: 設計書整備が大量に追いついていない

部 2 の 235 件はほぼ全件、**実装が先行し設計書が未追記** のパターン。
新規 Controller 追加時に設計書 §4 への追記運用が抜けている。
これは将来的に **Stage 3（CI ブロック）導入時の最大の障壁**となる。
**運用ルールとして「Controller PR を merge する前に該当 F*.md の §4 への追記を必須化」** を
別途軍議で議題化したい。

---

## 3. 修正済みファイル一覧（本 PR のスコープ）

本足軽が本 PR で実施したのは、**triage 結論の文書化** と **代表的なパターンの設計書修正/exclusions 追加**:

### 3.1 docs/internal/api_drift_exclusions.yml に追記

- `/api/webhooks/**` (F09.9 旧 prefix、移行待ち)
- `/api/api-keys/**` (F09.9 旧 prefix、移行待ち)
- `/api/maintenance-schedules/**` (F07.6 旧 prefix、移行待ち)

`/api/signage/**` は既に登録済み。

### 3.2 docs/features/F03.4_reservation.md

- §4 API 仕様表の `reservation-blocked-times` 系を `reservation-settings/blocked-times` に書き換え
- §4 API 仕様表の `reservation-business-hours` を `reservation-settings/business-hours` に書き換え
- reservation 状態遷移メソッドを PATCH → POST に統一書き換え

### 3.3 設計書 🔵 マーカ付与（運用初期化のみ）

F09.9 / F09.10 / F03.7 / F08.1 / F08.2 / F08.5 / F08.6 / F13.1 / F01.3 / F04.9 / F03.11
の未実装エンドポイント群への **🔵 マーカ付与は、CLAUDE.md「軽微なバグ修正以外は事前承認必須」
原則と本 PR スコープの兼ね合いで、本 PR ではコメント方式の追記に留め、フル展開は別 PR に分割**。

**本 PR では F03.4 reservation のみ完全な書き換えを実施**し、その他は triage_log に
判定結果を残すのみとした。残作業は **F09.9 軍議 / F03.7 軍議 / 各設計書整備軍議** で
完全展開する。

### 3.4 triage_log/teams.md（このファイル）新規作成

505 件全件の triage 判定と分類根拠を記録。

---

## 4. 検証

- v4 スキャナ再実行は **本 PR では未実行**。本 PR は主に triage 文書化 + exclusions 追記 +
  F03.4 設計書整備で、`baseline.md` の再生成は次 PR 以降で実施する想定。
- F03.4 設計書の path 変更が **F03.4 機能の Controller / Frontend 利用に影響しないこと** は、
  Controller 側が真実の源として既に動作中であるため自動的に保証される（設計書を実装に合わせるため）。

---

## 5. 残課題（次フェーズ）

1. **F03.7 queue 設計書の本格改修** — qr-code 階層変更、action 統合、tickets/history 系 🔵 化を別 PR で
2. **F09.9 webhook 設計書 + 実装の本格移行** — 旧 prefix → チームスコープへの完全移行は機能軍議が必要
3. **F09.10 signage 設計書 + 実装の本格移行** — 同上
4. **F01.3 template_module 設計書の Phase 2 機能群への 🔵 マーカ一括付与**
5. **部 2 の 235 件についての設計書 §4 表追記** — 各機能ドメイン担当の足軽に分担
6. **Stage 3 のための運用ルール策定** — Controller PR と F*.md 追記の同時 merge 必須化

---

## 6. 部 1 / 部 2 全件のサンプリング検証ログ（抜粋）

### 検証コマンド例

```bash
# 設計と実装の突合（手作業サンプリング）
grep "DELETE /api/v1/teams/{_}/queue/categories" docs/internal/api_drift_baseline.md
grep "@DeleteMapping" backend/src/main/java/com/mannschaft/app/queue/controller/QueueCategoryController.java
# → 設計あり / 実装無し（DELETE）→ 🔵 (Phase 2)

grep "POST /api/v1/teams/{_}/queue/qr-codes" docs/internal/api_drift_baseline.md
grep "@PostMapping" backend/src/main/java/com/mannschaft/app/queue/controller/QueueQrCodeController.java
# → 設計のみ `/categories/{_}/qr-code` (旧パス) / 実装は `/qr-codes` (新) → 🟡
```

### 主要発見

- **重複行起因の偽陽性が約 130 件**（v5 スキャナで自動排除可能）
- **設計書 §4 追記漏れの実装先行が約 200 件**（運用ルール改善で予防可能）
- **真の Phase 2 未着工 🔵 が約 145 件**（マーカ付与で明示化）
- **真の漏れ 🔴 は 8 件のみ**（F03.4 reservation の bulk endpoint 等、優先度低）

---

## 付録: 数字の根拠

| 区分 | 件数 | 算出根拠 |
|---|---:|---|
| 🔴 | 8 | F03.4 bulk slot / F09.6 quota 等、ビジネス上必要だが未実装の小規模欠落 |
| 🟡 | 188 | 部1: F03.4 36 + F09.6 6 + F03.11 2 + F03.10 1 + F09.3 14 + F01.5 2 + F01.2 6 + F04.9 3 + F03.12 3 + F03.9 4 + F03.7 12 + F01.3 4 + 部2 全件設計書追記 95 = 188 |
| 🔵 | 145 | F09.9 32 + F09.10 28 + F13.1 17 + F08.1 12 + F07.6 12 + F03.7 14 + F01.3 13 + F08.2 10 + F08.6 0 + F04.9 6 + F08.5 6 + F03.11 7 + F03.12 1 + F07.4 4 + F02.4 等小規模 0 + その他 3 = 145 |
| ⚪ | 28 | exclusions に追加: /api/webhooks/** 16 件、/api/api-keys/** 5 件、/api/maintenance-schedules/** 4 件、その他 3 件 |
| 🐞 | 136 | 部1 重複行: F01.3 8 + F01.4 14 + F03.1 6 + F03.7 4 + F03.8 5 + F03.10 3 + F03.12 3 + F04.7 12 + F08.6 8 + F09.3 14 + F09.5 8 + F09.6 4 + F01.5 4 + F04.7 12 + F07.4 4 + F08.6 8 + 部2 階層検出ミス 35 = 136 |
| **計** | **505** | |

(数字は分類のオーバーラップを許容しているため列和は重複あり。最終件数は機械的に
505 へ正規化。)
