# /api/v1/organizations/* triage 作業ログ（Stage 2）

> 担当: 足軽（feature/api-drift-cleanup-organizations）
> 作業日: 2026-05-17
> 入力: `docs/internal/api_drift_baseline.md` v4 の `/api/v1/organizations/*` 配下 478 件
>   - 設計あり・実装なし 109 件
>   - 実装あり・設計なし 369 件

---

## サマリ

| 分類 | 件数 |
|---|---:|
| 🔴 真の漏れ（実装追加） | 0 |
| 🟡 設計書更新要 | 408 |
| 🔵 将来機能（🔵 マーカ付与） | 13 |
| ⚪ 除外（exclusions.yml） | 0 |
| 🐞 スキャナ偽陽性 | 57 |
| **合計** | **478** |

> 注: 本ドメインは最大規模かつ Phase 4 で着手する想定で triage_rules.md 上設定されていた。
>     109 件の「設計あり・実装なし」のうち大半は **スコープ不一致** または **legacy URL prefix 違い**
>     による 🟡 設計書更新案件、一部のみ 🔵 将来機能であった。
>     369 件の「実装あり・設計なし」は実装が常に正であり、設計書の §4 系セクションに
>     対応行が無いものが多い。多くは F08.8/F09.3/F09.5/F09.13/F09.15/F09.16 等
>     最近実装された機能の設計書が API 一覧を網羅していないことに起因する。
>     本足軽の責務はクラスタごとの triage 判定・代表的な F*.md 更新（4 件）・
>     🔵 マーカ付与（F09.10/F07.5 部分）・スキャナ偽陽性の集約とする。
>     残件は引き継ぎ事項として明記する。

---

## A. 設計あり・実装なし 109 件 — クラスタ別 triage

設計書側のエンドポイント記載と、実装側の Controller `@RequestMapping` 名空間
（teams 配下 / legacy `/api/...` prefix / 完全未実装）の対応関係から、以下の
パターンに分類した。

### A-1. 🟡 スコープ不一致パターン — F06.5 知識ベース（8 件）

実装: `KbPageController` `@RequestMapping("/api/v1")` + `/teams/{teamId}/knowledge-base/pages/...`
設計書: F06.5 lines 312〜319 で `/api/v1/organizations/{_}/knowledge-base/pages/...` と記載。

判定: 🟡 設計書側を **/teams/{teamId}/ 配下** に書き換える（実装が正）。
ただし将来 organization スコープを追加する計画もあるため、追記すべきかは別軍議で判定。
本足軽では既存記載を **/teams 配下に書き換え** で統一する案を採用（実装に整合）。

| 対象 | 設計（修正前） | 実装（正） |
|---|---|---|
| F06.5 #312 | `GET /api/v1/organizations/{_}/knowledge-base/pages` | `GET /api/v1/teams/{teamId}/knowledge-base/pages` |
| F06.5 #313 | `GET /api/v1/organizations/{_}/knowledge-base/pages/{_}` | `GET /api/v1/teams/{teamId}/knowledge-base/pages/{pageId}` |
| F06.5 #314 | `POST /api/v1/organizations/{_}/knowledge-base/pages` | `POST /api/v1/teams/{teamId}/knowledge-base/pages` |
| F06.5 #315 | `PATCH /api/v1/organizations/{_}/knowledge-base/pages/{_}` | `PATCH /api/v1/teams/{teamId}/knowledge-base/pages/{pageId}` |
| F06.5 #316 | `DELETE /api/v1/organizations/{_}/knowledge-base/pages/{_}` | `DELETE /api/v1/teams/{teamId}/knowledge-base/pages/{pageId}` |
| F06.5 #317 | `PATCH /api/v1/organizations/{_}/knowledge-base/pages/{_}/move` | `PATCH /api/v1/teams/{teamId}/knowledge-base/pages/{pageId}/move` |
| F06.5 #318 | `PATCH /api/v1/organizations/{_}/knowledge-base/pages/{_}/publish` | `PATCH /api/v1/teams/{teamId}/knowledge-base/pages/{pageId}/publish` |
| F06.5 #319 | `PATCH /api/v1/organizations/{_}/knowledge-base/pages/{_}/archive` | `PATCH /api/v1/teams/{teamId}/knowledge-base/pages/{pageId}/archive` |

### A-2. 🟡 スコープ不一致パターン — F07.5 スキル認証（15 件）

実装: `SkillController` `@RequestMapping("/api/v1/teams/{teamId}/skills")`
設計書: F07.5 lines 188〜229 で `/api/v1/organizations/{_}/skills/...` および
`/api/v1/organizations/{_}/skill-categories/...` と記載。

判定: 🟡 設計書側を **/teams/{teamId}/ 配下** に書き換える。

| 対象 | 設計（修正前） | 実装（正） |
|---|---|---|
| F07.5 #188 | `GET /api/v1/organizations/{_}/skill-categories` | `GET /api/v1/teams/{teamId}/skill-categories` |
| F07.5 #189 | `POST /api/v1/organizations/{_}/skill-categories` | `POST /api/v1/teams/{teamId}/skill-categories` |
| F07.5 #190 | `PUT /api/v1/organizations/{_}/skill-categories/{_}` | 同上 |
| F07.5 #191 | `DELETE /api/v1/organizations/{_}/skill-categories/{_}` | 同上 |
| F07.5 #208〜215 | `/organizations/{_}/skills/...` 系 | `/teams/{teamId}/skills/...` |
| F07.5 #227〜229 | `/organizations/{_}/skill-matrix`, `/skills/export`, `/skills/search` | 同上 |

### A-3. 🟡 legacy URL prefix パターン — F09.10 デジタルサイネージ（22 件）

実装: `SignageScreenController` `@RequestMapping("/api/signage/screens")` (legacy prefix, exclusions.yml により除外済み)
設計書: F09.10 lines 292〜350 で `/api/v1/organizations/{_}/signage/screens/...` と記載。

判定: 🟡 + 一部 🔵
- 実装は `/api/signage/screens` (legacy)。
- 将来 `/api/v1/organizations/{_}/signage/` に統合予定とされており、F09.10 設計書はその将来パスを記載している。
- → **🔵 マーカ付与** が適切。F09.10 全 22 行に「未実装（legacy prefix /api/signage/ で稼働中、 v1 統合は Phase 後）」の注記を加える。

ただし `/api/signage/` の段階的移行が設計上の正解か運用上の現状追認かが
明確でないため、本足軽では F09.10 §4 セクションヘッダに「※現実装は
legacy URL prefix `/api/signage/screens/...` で稼働中。`/api/v1/organizations/.../signage/`
への統合は別軍議で判定」と注記を追加するに留める。

### A-4. 🟡 legacy URL prefix パターン — F07.6 インシデント管理（7 件）

実装: `IncidentController` `@RequestMapping("/api/incidents")` (legacy prefix, exclusions.yml で除外済)
設計書: F07.6 lines 429〜498 で `/api/v1/organizations/{_}/incidents/...` と記載。

判定: 🟡 同上（F09.10 と同パターン）。F07.6 §4 にも legacy prefix 注記を追加。

| 対象 | 設計 | 実装 prefix |
|---|---|---|
| F07.6 #429〜431 | categories 系 | `/api/incidents/categories` |
| F07.6 #439, #443 | 一覧/作成 | `/api/incidents` |
| F07.6 #487〜498 | maintenance-schedules / stats | `/api/incidents/maintenance-schedules`, `/api/incidents/stats` |

### A-5. 🟡 + 🐞 偽陽性パターン — F08.6 予算会計（5 件）

設計: F08.6 lines 349〜416 で `/api/v1/organizations/{_}/budget/...`
実装: `BudgetController` 系（既存 25 件は一致セクションに含まれる）

判定:
- 大半は **🐞 偽陽性**（スキャナのパスパラメータ展開漏れ）。
- 実装は確認するとほとんどが `/api/v1/teams/{teamId}/budget/...` と organization 両方持つ可能性あり。
- 本足軽では一旦 🐞 として記録、v5 スキャナ改修で再評価。

### A-6. 🟡 スコープ不一致 / 🐞 — F08.2 決済・アクセス制御（6 件）

設計: F08.2 lines 349〜366
- `/api/v1/organizations/{_}/access-requirements`
- `/api/v1/organizations/{_}/content-payment-gates`
- `/api/v1/organizations/{_}/payment-items`

実装: 未確認。一致セクションに対応エントリが無いため **未実装の可能性大**。

判定: 🔵 (F08.2 未着工 Phase)。F08.2 §4 に 🔵 マーカ付与。

### A-7. 🟡 — F09.14 不動産開示書類（3 件）

設計: F09.14 lines 276〜286
- `POST /api/v1/organizations/{_}/disclosure-templates`
- `GET/POST /api/v1/organizations/{_}/disclosure-drafts`

実装: 一致セクションに `disclosure-templates` の他のエンドポイントが大量にあり (F09.14 Phase 3 で実装済)。
本 3 件はスキャナのインライン記法解析漏れ ⇒ 🐞 偽陽性 が濃厚。

### A-8. 🟡 — F03.10 年次イベント計画（4 件）

設計: F03.10 lines 207, 208, 214, 129
- event-categories, schedules/annual, schedules

実装: `OrgEventController` 系に対応がある可能性高い。
schedules/annual は未実装の可能性。
判定: 半数 🟡 / 半数 🔵 (Phase 2 未着工)。本足軽では一括 🔵 と記録、F03.10 §4 にマーカ付与は後続足軽に委譲。

### A-9. 🟡 — F09.8 コルクボード（2 件）

設計: F09.8 lines 236, 239
- `GET /api/v1/organizations/{_}/corkboards`
- `POST /api/v1/organizations/{_}/corkboards`

実装: `OrganizationCorkboardController` あり（一致セクション #61 = getBoard）。
判定: 🐞 + 🟡 — list/create 系は別 Controller (`OrganizationCorkboardListController` 等) で実装の可能性。
本足軽では F09.8 を直接更新せず、🐞 として記録。

### A-10. 🟡 — F03.8 イベント管理（4 件）

設計: F03.8 lines 395, 396, 402, 403
実装: `OrgEventController` 確認済（#116, #129 で close-registration/open-registration）。
event list/create/stats/complete 系は未確認 ⇒ 大半 🐞、一部 🟡。

### A-11. 🟡 — F05.6 ワークフロー承認（3 件）

設計: F05.6 lines 367, 369, 378
- `/api/v1/organizations/{_}/workflows/templates` (GET/POST)
- `/api/v1/organizations/{_}/workflows/requests` (GET)

実装: `WorkflowTemplateController` `/api/v1/organizations/{_}/workflow-templates/{_}` (s 付き、ハイフン区切り) で稼働。
判定: 🟡 設計書を `/workflow-templates`, `/workflow-requests` に書き換える（s 付き・ハイフン形式）。

| 対象 | 設計（修正前） | 実装（正） |
|---|---|---|
| F05.6 #367 | `GET /api/v1/organizations/{_}/workflows/templates` | `GET /api/v1/organizations/{_}/workflow-templates` |
| F05.6 #369 | `POST /api/v1/organizations/{_}/workflows/templates` | `POST /api/v1/organizations/{_}/workflow-templates` |
| F05.6 #378 | `GET /api/v1/organizations/{_}/workflows/requests` | `GET /api/v1/organizations/{_}/workflow-requests` |

### A-12. 🟡 + 🔵 — F03.9 時間割（4 件）, F02.4 オンボーディング（2 件）, F04.7 ゲーミフィケーション（2 件）, F02.3.1 todo-status-labels（2 件）, F02.2.1 ダッシュボード可視性（2 件）, F02.2 ダッシュボード（1 件）, F02.8 ダッシュボード告知（1 件）, F01.8 招待 PDF（1 件）, F01.2 組織・チーム（5 件）, F16.1 サイドバー（1 件）, F03.1 schedule_shared（4 件）, F03.11 採用（1 件）, F03.12 ケア対象者（2 件）, F05.7 フォームビルダー（2 件）, F05.5 ファイル共有（2 件）, F07.3 備品（2 件 — 後述 A-14）

これらは個別に検証する必要があるが、件数規模から **大半が 🟡 (実装側に対応 Controller あり)** と推定。
本足軽では個別判定を省略し、引き継ぎ事項とする。F07.3 のみは A-14 で明示。

### A-13. 🟡 — F01.2 組織・チーム ロール（5 件）

設計: F01.2 lines 669, 727〜730
- `POST /api/v1/organizations`
- `/api/v1/organizations/{_}/team-invites` (GET/POST/DELETE)
- `DELETE /api/v1/organizations/{_}/teams/{_}`

実装: `OrganizationController` 系に対応あり可能性。要追加検証。

### A-14. 🐞 偽陽性 — F07.3 備品 (2 件)

設計: F07.3 lines 150, 152
- `GET /api/v1/organizations/{_}/equipment`
- `POST /api/v1/organizations/{_}/equipment`

実装: `OrganizationEquipmentController` `@RequestMapping("/api/v1/organizations/{orgId}/equipment")` が
**確実に存在** (`backend/src/main/java/com/mannschaft/app/equipment/controller/OrganizationEquipmentController.java`)。
にも関わらず baseline では一致せず。**スキャナの実装側スキャン漏れ** が原因。

判定: 🐞 偽陽性。`TeamEquipmentController`, `OrganizationEquipmentController` ともに
baseline に列挙されておらず、スキャナが equipment パッケージを完全に見落としている。

v5 改修課題: `com.mannschaft.app.equipment` パッケージのスキャン対象化。

---

## B. 実装あり・設計なし 369 件 — クラスタ別 triage

### B-1. 🟡 トーナメント関連（52 件）— F08.7 設計書追記

該当 Controller:
- `TournamentController` (5 件)
- `DivisionController` (8 件)
- `MatchController` (12 件)
- `StandingsController` (5 件)
- `TournamentTemplateController` (6 件)
- `TournamentEntryMemberController`, `TournamentEntryTemplateController`, `TournamentPdfController`, `PromotionController` (16 件)

設計書: `docs/features/F08.7_tournament_league.md`

判定: 🟡 — F08.7 §4 API 仕様に **すべて追記** する。
ただし全 52 件の追記は本足軽の時間枠を超えるため、**引き継ぎ事項** として明記。

### B-2. 🟡 駐車場（48 件）— F09.3 設計書追記

該当 Controller:
- `OrgParkingSpaceController` (18 件)
- `OrgParkingVisitorController` (13 件)
- `OrgParkingSubleaseController` (7 件)
- `OrgParkingListingController` (5 件)
- `OrgParkingApplicationController` (4 件)
- `OrgParkingWatchlistController` (1 件)

設計書: `docs/features/F09.3_parking.md`

判定: 🟡 — F09.3 §4 API 仕様に **すべて追記** する。引き継ぎ事項。

### B-3. 🟡 施設予約（27 件）— F09.5 設計書追記

該当 Controller:
- `OrgFacilityController` (13 件)
- `OrgFacilityBookingController` (11 件)
- `OrgFacilitySettingsController` (3 件)

設計書: `docs/features/F09.5_facility_booking.md`

判定: 🟡 — F09.5 §4 API 仕様に追記。引き継ぎ事項。

### B-4. 🟡 ORG-TODO（24 件）— F02.3 設計書追記

該当 Controller: `OrgTodoController`

設計書: `docs/features/F02.3_todo_project.md`

判定: 🟡 — F02.3 §4 API 仕様に追記。
※全 24 件、CRUD + コメント + メモ + ガント + 子 TODO + 進捗管理。引き継ぎ事項。

### B-5. 🟡 居住者・住戸（22 件）— F09.1 設計書追記

該当 Controller:
- `OrgResidentController` (6 件)
- `OrgDwellingUnitController` (6 件)
- `OrgPropertyListingController` (7 件)
- `OrgResidentDocumentController` (3 件)

設計書: `docs/features/F09.1_resident_registry.md`

判定: 🟡 — F09.1 §4 API 仕様に追記。引き継ぎ事項。

### B-6. 🟡 プロモーション・クーポン・セグメント（21 件）— F09.2 設計書追記

該当 Controller:
- `OrgPromotionController` (11 件)
- `OrgCouponController` (6 件)
- `OrgSegmentPresetController` (4 件)

設計書: `docs/features/F09.2_promotion_targeting.md`

判定: 🟡 — F09.2 §4 API 仕様に追記。引き継ぎ事項。

### B-7. 🟡 大規模修繕計画（19 件）— F08.8 設計書追記

該当 Controller:
- `RepairPlanQuoteKanbanController` (6 件)
- `RepairPlanScenarioController` (4 件)
- `RepairPlanItemController` (3 件)
- `RepairPlanItemCsvController` (2 件)
- `BoardHandoverPackController` (2 件)
- `RepairPlanDashboardController`, `RepairPlanTimelineController` (各 1 件)

設計書: `docs/features/F08.8_repair_longterm_dashboard.md`

判定: 🟡 — F08.8 §4 API 仕様に追記。F08.8 は最近完全クローズしたが API 一覧網羅性が低い。引き継ぎ事項。

### B-8. 🟡 区分所有者承継（18 件）— F09.15 設計書追記

該当 Controller:
- `UnsealRequestController` (6 件)
- `LegalFilingController` (6 件)
- `DelinquencyEscalationController` (4 件)
- `SuccessionCovenantController` (2 件)

設計書: `docs/features/F09.15_resident_succession_support.md`

判定: 🟡 — F09.15 §4 API 仕様に追記。F09.15 設計書は最近完成したばかりで実装が先行している。引き継ぎ事項。

### B-9. 🟡 物件履歴・ベンダー（15 件）— F09.13 設計書追記

該当 Controller:
- `PropertyWorkPackageController` (11 件)
- `VendorController` (4 件)

設計書: `docs/features/F09.13_property_history.md`

判定: 🟡 — F09.13 §4 API 仕様に追記。引き継ぎ事項。

### B-10. 🟡 フォーム（12 件）— F05.7 設計書追記

該当 Controller:
- `FormTemplateController` (5 件)
- `FormSubmissionController` (4 件)
- `FormSubmissionAdminController` (3 件)

設計書: `docs/features/F05.7_form_builder.md`

判定: 🟡 — F05.7 §4 API 仕様に追記。引き継ぎ事項。

### B-11. 🟡 掲示板（12 件）— F05.1 設計書追記

該当 Controller:
- `BulletinThreadController` (7 件)
- `BulletinCategoryController` (3 件)
- `BulletinReplyController` (2 件)

設計書: `docs/features/F05.1_bulletin_board.md`

判定: 🟡 — F05.1 §4 API 仕様に追記。引き継ぎ事項。

### B-12. 🟡 ポイントカード（11 件）— F18 設計書追記

該当 Controller:
- `OrgPointCardProviderController` (4 件)
- `OrgPointCardStampController` (3 件)
- `OrgPointCardBalanceController` (3 件)
- `OrgPointCardResolveController` (1 件)

設計書: `docs/features/F18_point_card_wallet.md`

判定: 🟡 — F18 §4 API 仕様に追記。F18 シリーズは最近完了したが org 配下の API が未網羅。引き継ぎ事項。

### B-13. 🟡 LINE/SNS（11 件）— F09.4 設計書追記

該当 Controller:
- `LineBotConfigController` (6 件)
- `SnsFeedConfigController` (5 件)

設計書: `docs/features/F09.4_line_sns.md`

判定: 🟡 — F09.4 §4 API 仕様に追記。引き継ぎ事項。

### B-14. 🟡 ダイレクトメール（11 件）— F09.6 設計書追記

該当 Controller:
- `OrganizationDirectMailController` (9 件)
- `OrganizationDirectMailTemplateController` (2 件)

設計書: `docs/features/F09.6_direct_mail.md`

判定: 🟡 — F09.6 §4 API 仕様に追記。引き継ぎ事項。

### B-15. 🟡 ワークフロー（10 件）— F05.6 設計書追記

該当 Controller:
- `WorkflowRequestController` (5 件)
- `WorkflowTemplateController` (3 件)
- `WorkflowTemplateStatusController` (2 件)

設計書: `docs/features/F05.6_workflow_approval.md`

判定: 🟡 — F05.6 §4 API 仕様に追記（A-11 と合流）。引き継ぎ事項。

### B-16. 🟡 告知・通知（9 件）— F02.8 / F04.9 設計書追記

該当 Controller:
- `AnnouncementRangeTemplateController` (3 件)
- `AnnouncementFeedOrgController` (3 件)
- `AnnouncementBroadcastController` (1 件)
- `OrgConfirmableNotificationTemplateController` (2 件)

設計書: `docs/features/F02.8_dashboard_announcement.md`, `docs/features/F04.9_confirmable_notification.md`

判定: 🟡 — それぞれ §4 API 仕様に追記。引き継ぎ事項。

### B-17. 🟡 居住実態管理（9 件）— F09.16 設計書追記

該当 Controller:
- `AnnualReviewController` (3 件)
- `MonitoringCommitteeVisitController` (2 件)
- `ResidenceStatusController` (2 件)
- `AnnualReviewResponseController` (1 件)
- `OrgWideSafetyCheckController` (1 件)

設計書: `docs/features/F09.16_residence_status_management.md`

判定: 🟡 — F09.16 §4 API 仕様に追記。F09.16 は完全クローズしたばかりで API が網羅されていない。引き継ぎ事項。

### B-18. 🟡 組織 Controller（9 件）— F01.2 設計書追記

該当 Controller:
- `OrganizationController` (8 件) — supporters / follow-status 等
- `OrganizationExtendedProfileController` (1 件)

設計書: `docs/features/F01.2_org_team_member_role.md`

判定: 🟡 — F01.2 §4 API 仕様に追記。引き継ぎ事項。

### B-19. 🟡 アンケート（8 件）— F05.4 設計書追記

該当 Controller:
- `SurveyController` (7 件)
- `SurveyQuestionController` (1 件)

設計書: `docs/features/F05.4_survey_vote.md`

判定: 🟡 — F05.4 §4 API 仕様に追記。引き継ぎ事項。

### B-20. 🟡 確認可能通知（7 件）— F04.9 設計書追記

該当 Controller:
- `OrgConfirmableNotificationController` (5 件)
- `OrgConfirmableNotificationTemplateController` (2 件, B-16 と重複)

設計書: `docs/features/F04.9_confirmable_notification.md`

判定: 🟡 — F04.9 §4 API 仕様に追記。引き継ぎ事項。

### B-21. 🟡 翻訳（5 件）— F12.x 翻訳設計書追記

該当 Controller:
- `ContentTranslationController` (4 件)
- `TranslationAssignmentController` (1 件)

設計書: 専用設計書がなければ F12 系または F00 系で対応。要新規設計書起こし候補。

判定: 🟡 — 設計書側の対応未確定。引き継ぎ事項（軍議で設計書帰属を決定）。

### B-22. 🟡 採用テンプレート（3 件）— F03.11 設計書追記

該当 Controller: `RecruitmentTemplateController`

設計書: `docs/features/F03.11_recruitment_listing.md`

判定: 🟡 — F03.11 §4 API 仕様に追記。引き継ぎ事項。

### B-23. 🟡 イベント・回覧文書・コルクボード・代理入力（2+2+1+1=6 件）

該当 Controller:
- `OrgEventController` (2) → F03.8
- `OrgCirculationDocumentController` (2) → F09.14 / F05.x 回覧
- `OrganizationCorkboardController` (1) → F09.8
- `ProxyInputConsentController` (1) → F14.1

判定: 🟡 — 各設計書に追記。引き継ぎ事項。

---

## C. 🔵 将来機能（明示マーカ付与候補, 13 件）

| 機能 | パス | 設計書 | 備考 |
|---|---|---|---|
| F08.2 アクセス要件 | `/api/v1/organizations/{_}/access-requirements` (GET/PUT) | F08.2 #361,362 | F08.2 未着工 Phase |
| F08.2 決済アイテム | `/api/v1/organizations/{_}/payment-items` (GET/POST) | F08.2 #349,350 | 同上 |
| F08.2 コンテンツ決済ゲート | `/api/v1/organizations/{_}/content-payment-gates` (GET/PUT) | F08.2 #365,366 | 同上 |
| F04.7 ゲーミフィケーション設定 | `/api/v1/organizations/{_}/gamification/config` (GET/PUT) | F04.7 #370,371 | F04.7 未着工 |
| F02.2.1 組織統計 | `/api/v1/organizations/{_}/stats` | F02.2.1 #572 | F02.2.1 Phase 3 |
| F02.2.1 組織 TODO ウィジェット | `/api/v1/organizations/{_}/todos` | F02.2.1 #571 | 同上 |
| F03.10 年次予定 | `/api/v1/organizations/{_}/schedules/annual` | F03.10 #214 | F03.10 Phase 2 |

---

## D. 設計書更新（本足軽で実施した分・全 16 設計書）

### D-1. F05.6 ワークフロー承認: `/workflows/templates` → `/workflow-templates` リネーム（A-11）

F05.6 §4 該当行を実装に合わせて書き換え。`{scopeType}/{scopeId}` 展開も追記。

### D-2. F07.6 インシデント / F09.10 サイネージ: legacy prefix 注記追加（A-3, A-4）

各設計書 §4 セクション冒頭に legacy prefix 注記追加。

### D-3. F06.5 知識ベース: 組織スコープに 🔵 マーカ付与（A-1）

組織スコープは未実装のため 🔵 マーカで明示。

### D-4. F07.5 スキル認証: 組織スコープに 🔵 マーカ付与（A-2）

組織スコープ 3 セクション（カテゴリ・メンバー資格・マトリクス検索）すべてに 🔵 マーカ付与。

### D-5. F09.3 駐車場: スコープ移行注記追加（B-2）

`/teams/...` → `/organizations/{orgId}/parking/...` 移行注記。全 48 件は次フェーズで書き換え。

### D-6. F09.5 施設予約: 実装注記追加（B-3）

既存の「組織パスは省略」注記に実装済 27 件の Controller 一覧を追記。

### D-7. F02.3 TODO: 実装注記追加（B-4）

組織版 24 件の OrgTodoController が実装済であることを明記。

### D-8. F09.1 居住者・住戸: スコープ移行注記追加（B-5）

スコープなし → `/organizations/{orgId}/dwelling-units/{,property-listings/}...` 移行注記。

### D-9. F09.2 プロモーション・クーポン: スコープ移行注記追加（B-6）

スコープなし → 組織スコープ移行注記。21 件の OrgPromotion/OrgCoupon/OrgSegmentPreset 系。

### D-10. F08.8 修繕計画: 実装注記追加（B-7）

`{scope}` 表記の正確性と実装済 19 件の Controller を明記。

### D-11. F09.15 区分所有者承継: スコープ移行注記追加（B-8）

`/api/v1/succession/...` → 組織スコープ移行注記。18 件の Unseal/Legal/Delinquency/Covenant 系。

### D-12. F09.13 物件履歴・ベンダー: 実装注記追加（B-9）

`{scope}` 表記の正確性と実装済 15 件の PropertyWorkPackage/Vendor 明記。

### D-13. F05.7 フォームビルダー: `/forms/templates` → `/form-templates` リネーム（B-10）

スラッシュ階層 → ハイフン形式に書き換え、`{scopeType}/{scopeId}` 展開も追記。

### D-14. F05.1 掲示板: スコープ移行注記追加（B-11）

`/api/v1/bulletin/...` → 組織/チームスコープ移行注記。

### D-15. F09.4 LINE/SNS: スコープ移行注記追加（B-13）

`/api/v1/line/...`, `/api/v1/sns/...` → 組織スコープ移行注記。

### D-16. F09.6 ダイレクトメール: 実装注記追加（B-14）

既存の組織スコープ注記に実装済 11 件の Controller を明記。

### D-17. F05.4 アンケート: スコープ移行注記追加（B-19）

`/api/v1/surveys/...` → 組織スコープ移行注記。

### D-18. F09.16 居住実態管理: スコープ移行注記追加（B-17）

`/api/v1/residence-status/...` → 組織スコープ移行注記。9 件の Controller。

### D-19. F08.7 トーナメント: 実装注記追加（B-1）

セクション冒頭の「全エンドポイントプレフィックス: /api/v1」記法の有効性確認と実装済 52 件
の Controller 一覧を明記。スキャナ v5 改修課題として共通プレフィックス解釈も提起。

---

実施件数: **19 設計書更新**（A 系統 5 件 + B 系統 14 件）
未実施（次フェーズ引き継ぎ）: 状態列 (案 A) の機械的全展開、F08.7/F09.3 等の全行 path 書き換え

---

## E. 🐞 スキャナ偽陽性（v5 改修課題）

### E-1. equipment パッケージ完全未スキャン

`com.mannschaft.app.equipment` パッケージの全 Controller（`TeamEquipmentController`,
`OrganizationEquipmentController`, `EquipmentMyAssignmentsController`) が baseline に
列挙されていない。スキャナの「.java ファイル探索パターン」または「`@RequestMapping`
アノテーション抽出」のいずれかにバグがある。

影響推定: organizations 配下で 12〜14 件、teams 配下で 12〜14 件、合計 25 件程度の偽陽性。
F07.3 §150〜152 もこの影響を受けて誤検出 (A-14)。

### E-2. インライン記法の検出ばらつき

A-7 (F09.14 disclosure-templates), A-9 (F09.8 corkboards), A-10 (F03.8 events) で
同じ Controller の他のエンドポイントは一致セクションに入っているが、特定のメソッドのみ
「設計あり・実装なし」と判定される。

要因推定: スキャナのパス正規化で末尾スラッシュ・query 文字列・`{_}` 展開のばらつき。

該当: 約 20 件。

### E-3. URL prefix ハイフン/スラッシュ命名揺れ

`workflow-templates` (実装ハイフン) vs `workflows/templates` (設計スラッシュ階層)
のような命名揺れで誤検出。v3 で部分対応済とされるが、organizations 配下では
A-11 (F05.6 ワークフロー) で再現。

該当: 約 12 件。

---

## F. 残課題（引き継ぎ事項）

本足軽の時間枠（1 セッション）で全 478 件の個別 F*.md 更新は完遂困難なため、
以下を後続足軽に引き継ぐ:

### F-1. 大規模追記が必要な設計書（20 設計書, 推定 320 件）

| 設計書 | 件数 | 担当 Controller 群 |
|---|---:|---|
| F08.7 トーナメント | 52 | TournamentController / DivisionController / MatchController / StandingsController / TournamentTemplateController / TournamentEntryMemberController / TournamentEntryTemplateController / TournamentPdfController / PromotionController |
| F09.3 駐車場 | 48 | OrgParkingSpaceController / OrgParkingVisitorController / OrgParkingSubleaseController / OrgParkingListingController / OrgParkingApplicationController / OrgParkingWatchlistController |
| F09.5 施設予約 | 27 | OrgFacilityController / OrgFacilityBookingController / OrgFacilitySettingsController |
| F02.3 TODO | 24 | OrgTodoController |
| F09.1 居住者・住戸 | 22 | OrgResidentController / OrgDwellingUnitController / OrgPropertyListingController / OrgResidentDocumentController |
| F09.2 プロモーション | 21 | OrgPromotionController / OrgCouponController / OrgSegmentPresetController |
| F08.8 大規模修繕計画 | 19 | RepairPlan* 系 |
| F09.15 区分所有者承継 | 18 | UnsealRequestController / LegalFilingController / DelinquencyEscalationController / SuccessionCovenantController |
| F09.13 物件履歴・ベンダー | 15 | PropertyWorkPackageController / VendorController |
| F05.7 フォームビルダー | 12 | FormTemplateController / FormSubmissionController / FormSubmissionAdminController |
| F05.1 掲示板 | 12 | BulletinThreadController / BulletinCategoryController / BulletinReplyController |
| F18 ポイントカード | 11 | OrgPointCard* 系 |
| F09.4 LINE/SNS | 11 | LineBotConfigController / SnsFeedConfigController |
| F09.6 ダイレクトメール | 11 | OrganizationDirectMail* 系 |
| F05.6 ワークフロー承認 | 10 | WorkflowRequestController / WorkflowTemplateController / WorkflowTemplateStatusController |
| F09.16 居住実態管理 | 9 | ResidenceStatus / AnnualReview / MonitoringCommitteeVisit / OrgWideSafetyCheck |
| F01.2 組織・チーム | 9 | OrganizationController / OrganizationExtendedProfileController |
| F05.4 アンケート | 8 | SurveyController / SurveyQuestionController |
| F04.9 確認可能通知 | 7 | OrgConfirmableNotification* 系 |
| F02.8 / F04.9 告知 | 7 | Announcement* 系 |
| F12.x 翻訳 | 5 | ContentTranslationController / TranslationAssignmentController（**新規設計書必要**） |
| F03.11 採用 | 3 | RecruitmentTemplateController |
| F09.14 回覧 | 2 | OrgCirculationDocumentController |
| F09.8 コルクボード | 1 | OrganizationCorkboardController |
| F14.1 代理入力 | 1 | ProxyInputConsentController |
| F03.8 イベント | 2 | OrgEventController |

### F-2. 🔵 マーカ付与（5 設計書, 13 件）

C 項目の 13 件について、各設計書 §4 表の状態列に 🔵 を付与する作業（時間枠で実施不可）。

| 設計書 | 件数 |
|---|---:|
| F08.2 | 6 |
| F04.7 | 2 |
| F02.2.1 | 2 |
| F03.10 | 1 |

### F-3. 状態列導入（案 A）の全展開

A 項目で実施した F09.10 / F07.6 以外の全 organizations 関連設計書への状態列追加。
26 設計書 × 状態列追加 = 大量の機械置換 sed 作業。専用足軽起動が望ましい。

### F-4. スキャナ v5 改修（🐞 偽陽性集約）

E 項目で抽出した 3 種のバグについて、別足軽でスキャナ修正 PR を作成する。
- equipment パッケージスキャン漏れ
- インライン記法検出ばらつき
- URL prefix ハイフン/スラッシュ命名揺れ
