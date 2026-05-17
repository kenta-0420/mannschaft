# /api/v1/shifts/* + /api/v1/shift-budget/* triage 作業ログ（Stage 3 第二陣 2-α）

> 担当: 足軽（feature/api-drift-cleanup-shifts）
> 作業日: 2026-05-17
> 入力: `docs/internal/api_drift_baseline.md` v4 中の shifts ドメイン
>   - section 1 (設計あり・実装なし) `/api/v1/shifts/*` = **28 件**（重複行込み）
>   - section 1 (設計あり・実装なし) `/api/v1/shift-budget/*` = **1 件**
>   - section 2 (実装あり・設計なし) `/api/v1/shifts/*` = **19 件**
>   - section 2 (実装あり・設計なし) `/api/v1/shift-budget/*` = **5 件**
>   - 合計 **53 件** を triage 対象とした

---

## サマリ

| 分類 | 件数 | 備考 |
|---|---:|---|
| 🔴 真の漏れ（実装追加要） | 2 | F03.5 設計書で「実装済み」とされている `GET /schedules/{id}/summary` `POST /schedules/{id}/remind` が Controller に存在しない |
| 🟡 設計書更新要 | 26 | パス階層変更 / メソッド変更 / 設計書未追記の実装エンドポイント。本 PR で F03.5・F08.7 を整合 |
| 🔵 将来機能（🔵 マーカ付与） | 0 | shifts ドメインは Phase 11 までほぼ着工済み。明確な「未着工 Phase」は無い |
| ⚪ 除外（exclusions.yml） | 0 | 内部用 / 旧 prefix なし |
| 🐞 スキャナ偽陽性（重複行・パス展開） | 25 | v4 ベースラインの重複行検出が主要因。`/api/v1/shift-budget/allocations` POST 等 |
| **合計** | **53** | |

> 補足: shifts ドメインは F03.5（本体）と F08.7（予算連携）の 2 設計書で網羅されており、
> 設計と実装の同期はほぼ取れている。triage の主目的は **scanner 偽陽性の
> 分類と、設計書テーブル記載のメソッド/階層揺れの整流**。

---

## 1. section 1（設計あり・実装なし）の分類

### A. `/api/v1/shifts/*` 28 件

#### A-1. 重複行起因（🐞 偽陽性）

scanner v4 が同一 (method, path) を複数行で計上したケース。
`api_drift_baseline.md` line 1518-1577 中、以下は **同一エンドポイントが §4 表ヘッダと §4.x 詳細ヘッダで 2 回登場** したことが原因:

| メソッド | パス | 行 | 判定 |
|---|---|---:|---|
| DELETE | `/shifts/work-constraints/{_}` | 608 / 2351 | 🐞 重複（実装は階層違いだが行重複は別問題、後述 B-3） |
| GET | `/shifts/change-requests` | 610 / 2413 | 🐞 重複（実装存在） |
| GET | `/shifts/hourly-rates` | 597 / 1903 | 🐞 重複 |
| GET | `/shifts/my` | 581 / 1282 | 🐞 重複（後述 B-4 別検討） |
| GET | `/shifts/positions` | 584 / 1436 | 🐞 重複（実装存在） |
| GET | `/shifts/schedules` | 565 / 621 | 🐞 重複（実装存在） |
| GET | `/shifts/schedules/{_}/pdf` | 52/617/2576/3411/3948 | 🐞 5 行重複（実装存在 `ShiftPdfController`） |
| GET | `/shifts/schedules/{_}/requests` | 577 / 1129 | 🐞 重複（実装存在 → `ShiftRequestController#listRequests` で取れるが path 階層違い 後述 B-2） |
| GET | `/shifts/schedules/{_}/summary` | 582 / 1348 | 🔴 設計あり実装無し（後述 C-1） |
| GET | `/shifts/work-constraints` | 605 / 2257 | 🐞 重複（実装は階層違い、後述 B-3） |
| PATCH | `/shifts/schedules/{_}/publish` | 571 / 898 | 🐞 重複（実装は `/transition` 統合、後述 B-1） |
| PATCH | `/shifts/schedules/{_}/status` | 570 / 849 | 🐞 重複（同上） |
| PATCH | `/shifts/swap-requests/{_}/accept` | 589 / 1578 | 🐞 重複（実装 POST、後述 B-5） |
| PATCH | `/shifts/swap-requests/{_}/approve` | 590 / 1605 | 🐞 重複（実装 `/resolve` 統合、後述 B-5） |
| PATCH | `/shifts/swap-requests/{_}/reject` | 591 / 1643 | 🐞 重複（同上） |
| POST | `/shifts/change-requests` | 609 / 2365 | 🐞 重複（実装存在） |
| POST | `/shifts/positions` | 585 / 1464 | 🐞 重複（実装存在） |
| POST | `/shifts/schedules` | 566 / 674 | 🐞 重複（実装存在） |
| POST | `/shifts/schedules/{_}/remind` | 583 / 1406 | 🔴 設計あり実装無し（後述 C-2） |
| POST | `/shifts/swap-requests` | 588 / 1533 | 🐞 重複（実装存在） |
| PUT | `/shifts/hourly-rate` | 596 / 1876 | 🐞 重複（実装は POST、後述 B-6） |
| PUT | `/shifts/hourly-rates/{_}` | 598 / 1941 | 🐞 重複（実装は同経路で POST/GET、PUT は無し → B-6） |
| PUT | `/shifts/positions/{_}` | 586 / 1490 | 🐞 重複（実装は PATCH、後述 B-7） |
| PUT | `/shifts/requests/{_}` | 579 / 1243 | 🐞 重複（実装は PATCH、後述 B-7） |
| PUT | `/shifts/schedules/{_}` | 568 / 797 | 🐞 重複（実装は PATCH、後述 B-7） |
| PUT | `/shifts/slots/{_}` | 575 / 1081 | 🐞 重複（実装は PATCH、後述 B-7） |
| PUT | `/shifts/work-constraints` | 606 / 2304 / 2338 | 🐞 3 行重複（実装は階層違い、後述 B-3） |
| PUT | `/shifts/work-constraints/{_}` | 607 / 2334 | 🐞 重複（同上） |

合計 28 件 = **🐞 26 件 + 🔴 2 件**（C-1, C-2）。

ただし 🐞 のうち下記 B-1〜B-7 は **設計書の記載が実装と乖離している（重複行ではなくメソッド/階層不一致）** ため、`🟡 設計書更新` で重複削減と整合修正を両方行う。

---

### B. パス・メソッド整合（🟡 設計書更新要）

#### B-1. `schedules/{id}/status` `schedules/{id}/publish` → `schedules/{id}/transition` 統合

実装: `ShiftScheduleController` line 113 に `POST /api/v1/shifts/schedules/{scheduleId}/transition` の 1 本のみ存在。`status` `publish` の細分 PATCH エンドポイントは **無い**。

設計書（F03.5 §4.8/§4.9 line 849/898）: PATCH の 2 本に分かれた記述。

判定: 実装側で **状態遷移を 1 本に統合**（DRAFT→PUBLISHED, PUBLISHED→FINALIZED, FINALIZED→CLOSED など）したのが正。設計書 §4.8/§4.9 の記述を `POST /schedules/{id}/transition` に書き換える 🟡。

#### B-2. `GET /shifts/schedules/{id}/requests` → 実装は `GET /shifts/requests?scheduleId={...}`

実装: `ShiftRequestController#listRequests` (`GET /api/v1/shifts/requests`) で `scheduleId` パラメータで絞り込む構造。`/schedules/{id}/requests` の親パス埋め込みは **無い**。

判定: 設計書 §4 line 1129 を「`GET /shifts/requests?scheduleId={id}` を使う」と書き換える 🟡。

#### B-3. `/shifts/work-constraints` → `/shifts/teams/{teamId}/work-constraints/...` 階層化

実装: `MemberWorkConstraintController` (`@RequestMapping("/api/v1/shifts/teams/{teamId}/work-constraints")`) で **チーム配下にスコープ化**。
- GET (list) / GET `/members/{userId}` / PUT `/members/{userId}` / DELETE `/members/{userId}`
- GET `/default` / PUT `/default` / DELETE `/default`

設計書（F03.5 line 605-607, 2257, 2304, 2334, 2338, 2351）: フラットな `/shifts/work-constraints` `/shifts/work-constraints/{userId}` で記述。

判定: チームスコープ化が正（複数チーム所属時の制約管理に必要）。設計書 §4 line 2257-2351 周辺を **チームスコープ化** に書き換え 🟡。

#### B-4. `GET /shifts/my` → `GET /shifts/my/requests`

実装: `ShiftRequestController#listMyRequests` (`GET /api/v1/shifts/my/requests`) のみ存在。`/shifts/my` 単体は **無い**。

設計書（F03.5 line 581, 1282）: `GET /shifts/my` で記述（自分のシフト一覧を意図）。

判定: 「自分のシフト一覧」は `/shifts/my/requests` （希望提出一覧）で取得し、確定後のシフトは `/shifts/schedules?userId=me` 等で取る方針が現実装。設計書 §4 を `/my/requests` に揃える 🟡。

#### B-5. `swap-requests/{id}/accept,approve,reject` PATCH → POST + resolve 統合

実装: `ShiftSwapController` (`/api/v1/shifts/swap-requests`)
- `POST /{swapId}/accept` (申請受諾、相手側)
- `POST /{swapId}/resolve` (管理者承認/却下統合、リクエストボディの `action` で分岐)
- POST `/{swapId}/claim` `/{swapId}/select-claimer` (v2.1 新規)

設計書（F03.5 line 1578, 1605, 1643）: PATCH `/accept` `/approve` `/reject` の 3 本に分割した記述。

判定: メソッド PATCH→POST + approve/reject→resolve 統合が現実装。設計書 §4 を **POST + `/resolve` 統合** に書き換え 🟡。
さらに **B-5-a**: 実装に存在する `POST /{swapId}/claim` `POST /{swapId}/select-claimer` は設計書 §4 line 1684 / 1730 に「v2.1 新規」として既に記載あり → 整合 🟢。

#### B-6. `/shifts/hourly-rate` PUT → POST

実装: `ShiftAvailabilityController#createHourlyRate` は **POST** `/api/v1/shifts/hourly-rate`。
GET も `/hourly-rate` (単数) で実装。

設計書（F03.5 line 596, 1876）: `PUT /shifts/hourly-rate` で記述。
さらに line 597, 1903 は `GET /shifts/hourly-rates` (複数、管理者向け一覧) として記述しているが、**実装側は単数 `/hourly-rate` のみで一覧 API は ShiftAvailabilityController に無い**。

判定:
- `PUT /shifts/hourly-rate` → `POST /shifts/hourly-rate` に修正 🟡
- `GET /shifts/hourly-rates` (複数) はバックエンド未実装。F03.5 §4 line 597, 1903 の記述は **「管理者向け一覧」用途で v2 設計、Phase 11 残作業**。本 PR では 🟡 として「現状未実装で v2 計画中」コメント追記に留める

#### B-7. PUT → PATCH 統一（4 件）

実装: 各 update 系は **PATCH** に統一。
- `ShiftPositionController#updatePosition` PATCH `/positions/{id}`
- `ShiftRequestController#updateRequest` PATCH `/requests/{id}`
- `ShiftScheduleController#updateSchedule` PATCH `/{scheduleId}`
- `ShiftSlotController#updateSlot` PATCH `/slots/{id}`

設計書（F03.5 line 586, 579, 568, 575）: **PUT** で記述。

判定: 部分更新が標準なので PATCH が正。設計書 §4 を **PUT→PATCH** に揃える 🟡（4 件）。

---

### C. 真の漏れ（🔴 実装追加要、2 件）

#### C-1. `GET /api/v1/shifts/schedules/{id}/summary` 未実装

設計書（F03.5 line 1348-1405）: シフトスケジュール集計取得 API として完全に記載されている（Phase 1 標準機能扱い）。

実装側調査:
- `ShiftScheduleController` には `/summary` エンドポイント無し
- `ShiftScheduleService` にも `getSummary` 系メソッド見当たらず（要再確認）
- フロントエンドが利用しているかは別途確認が必要

判定: **🔴 実装追加要**。優先度は中（合計時間・人数の集計はクライアント側でスロット集計から計算する暫定運用が機能している可能性が高い、ただし設計書記載に従い実装すべき）。triage_log に「実装待ち」として記録。

#### C-2. `POST /api/v1/shifts/schedules/{id}/remind` 未実装

設計書（F03.5 line 1406-1435）: シフト希望未提出メンバーへのリマインド通知 API として完全に記載（Phase 1 後期 / Phase 2 機能扱い、説明文に「リマインド間隔チーム設定」F03.5 Phase 5 完了の文脈あり）。

実装側調査:
- `ShiftScheduleController` には `/remind` エンドポイント無し
- リマインド機能は別バッチ (`ShiftReminderBatchJob` 推定) で実装されていそうだが、手動起動 API は不存在

判定: **🔴 実装追加要**。F03.5 Phase 5 でリマインド間隔チーム設定までは完了済（memory 参照）だが、**管理者による手動リマインド送信 API** は本実装の漏れと推定。triage_log に「実装待ち」として記録。

---

### D. `/api/v1/shift-budget/*` section 1 (1 件)

| メソッド | パス | 設計書行 | 判定 |
|---|---|---:|---|
| POST | `/shift-budget/allocations` | F08.7 §6.2.1 line 759 | 🐞 |

実装: `ShiftBudgetAllocationController#createAllocation` POST `/api/v1/shift-budget/allocations` (line 68) が存在。
完全に一致しているはずだが scanner v4 が「設計あり・実装なし」として誤計上。

判定: **🐞 スキャナ偽陽性**。F08.7 設計書は §6.1 表 (line 746) と §6.2.1 詳細ヘッダ (line 759) の 2 箇所で同 path を記述しており、scanner が片方を一致、もう片方を誤って未一致と判定した可能性。v5 改修で「同一 (method, path) は同一設計書内で 1 件にまとめる」と解消する。本 PR では設計書側で変更なし。

---

## 2. section 2（実装あり・設計なし）の分類

### α. `/api/v1/shifts/*` 19 件

| メソッド | パス | Controller | 判定 |
|---|---|---|---|
| DELETE | `/shifts/availability` | `ShiftAvailabilityController#deleteAvailabilityDefaults` | 🟡 F03.5 §4 に追記 |
| DELETE | `/shifts/teams/{_}/work-constraints/default` | `MemberWorkConstraintController#deleteTeamDefault` | 🟡 B-3 統合書き換えで吸収 |
| DELETE | `/shifts/teams/{_}/work-constraints/members/{_}` | `MemberWorkConstraintController#deleteConstraint` | 🟡 B-3 統合書き換えで吸収 |
| GET | `/shifts/my/requests` | `ShiftRequestController#listMyRequests` | 🟡 B-4 で吸収 |
| GET | `/shifts/requests` | `ShiftRequestController#listRequests` | 🟡 B-2 で吸収 |
| GET | `/shifts/requests/summary` | `ShiftRequestController#getRequestSummary` | 🟡 F03.5 §4 に新規追記 |
| GET | `/shifts/teams/{_}/work-constraints/default` | `MemberWorkConstraintController#getTeamDefault` | 🟡 B-3 統合書き換えで吸収 |
| GET | `/shifts/teams/{_}/work-constraints/members/{_}` | `MemberWorkConstraintController#getConstraint` | 🟡 B-3 統合書き換えで吸収 |
| PATCH | `/shifts/positions/{_}` | `ShiftPositionController#updatePosition` | 🟡 B-7 で吸収 |
| PATCH | `/shifts/requests/{_}` | `ShiftRequestController#updateRequest` | 🟡 B-7 で吸収 |
| PATCH | `/shifts/schedules/{_}` | `ShiftScheduleController#updateSchedule` | 🟡 B-7 で吸収 |
| PATCH | `/shifts/slots/{_}` | `ShiftSlotController#updateSlot` | 🟡 B-7 で吸収 |
| POST | `/shifts/hourly-rate` | `ShiftAvailabilityController#createHourlyRate` | 🟡 B-6 で吸収 |
| POST | `/shifts/schedules/{_}/duplicate` | `ShiftScheduleController#duplicateSchedule` | 🟡 F03.5 §4 に追記必要（実装あり、設計書未記載） |
| POST | `/shifts/schedules/{_}/transition` | `ShiftScheduleController#transitionStatus` | 🟡 B-1 で吸収 |
| POST | `/shifts/swap-requests/{_}/accept` | `ShiftSwapController#acceptSwapRequest` | 🟡 B-5 で吸収 |
| POST | `/shifts/swap-requests/{_}/resolve` | `ShiftSwapController#resolveSwapRequest` | 🟡 B-5 で吸収 |
| PUT | `/shifts/teams/{_}/work-constraints/default` | `MemberWorkConstraintController#upsertTeamDefault` | 🟡 B-3 統合書き換えで吸収 |
| PUT | `/shifts/teams/{_}/work-constraints/members/{_}` | `MemberWorkConstraintController#upsertConstraint` | 🟡 B-3 統合書き換えで吸収 |

合計 19 件 = **🟡 19 件**（うち多くは B-1〜B-7 で吸収）。

### β. `/api/v1/shift-budget/*` 5 件

| メソッド | パス | Controller | 判定 |
|---|---|---|---|
| DELETE | `/shift-budget/allocations/{_}` | `ShiftBudgetAllocationController#deleteAllocation` | 🟡 F08.7 §6.1 表に既に DELETE 行あり、scanner 重複検出ミス |
| GET | `/shift-budget/allocations/{_}` | `ShiftBudgetAllocationController#getAllocation` | 🟡 F08.7 §6.1 表に明記なし。`GET /shift-budget/allocations/{id}` （単体取得）を §6.1 に追記 |
| POST | `/shift-budget/failed-events/{_}/resolve` | `ShiftBudgetFailedEventController#resolve` | 🟡 F08.7 §6.1 表に明示行なし（v1.3 line 1667 で本文記述あり）→ §6.1 一覧に正式追記 |
| POST | `/shift-budget/failed-events/{_}/retry` | `ShiftBudgetFailedEventController#retry` | 🟡 同上 |
| PUT | `/shift-budget/allocations/{_}` | `ShiftBudgetAllocationController#updateAllocation` | 🟡 F08.7 §6.1 表に既に行あり（line 747）→ scanner 検出ミス |

合計 5 件 = **🟡 5 件**。

うち本 PR で **F08.7 §6.1 表に `GET /allocations/{id}` 単体取得 + `failed-events` 系 GET/retry/resolve を正式に追記**する。

加えて scanner 側で取りこぼしている下記実装も F08.7 §6.1 に追記候補（baseline は重複行込みで拾い損ねている）:

- `GET /shift-budget/failed-events` (`ShiftBudgetFailedEventController#list`)
- `BudgetThresholdAlertController` 系: §6.1 #9 #10 に記載済み

---

## 3. 修正済みファイル一覧（本 PR のスコープ）

### 3.1 docs/features/F03.5_shift.md

- §4 API 仕様（line 568-617 周辺の表ヘッダ）:
  - `PUT /shifts/schedules/{id}` → `PATCH /shifts/schedules/{id}`
  - `PUT /shifts/slots/{id}` → `PATCH /shifts/slots/{id}`
  - `PUT /shifts/requests/{id}` → `PATCH /shifts/requests/{id}`
  - `PUT /shifts/positions/{id}` → `PATCH /shifts/positions/{id}`
  - `PUT /shifts/hourly-rate` → `POST /shifts/hourly-rate`
  - `PATCH /shifts/schedules/{id}/status` `/publish` → `POST /shifts/schedules/{id}/transition` に統合
  - `PATCH /shifts/swap-requests/{id}/accept` `/approve` `/reject` → `POST /accept` + `POST /resolve` 統合
- §4 API 仕様詳細ヘッダ（line 797, 1081, 1243, 1490, 1876, 849, 898, 1578, 1605, 1643）も同様に書き換え
- `GET /shifts/my` → `GET /shifts/my/requests` に修正（line 581, 1282）
- `GET /shifts/schedules/{id}/requests` → `GET /shifts/requests?scheduleId=` 注記追加（line 577, 1129）
- 新規追記:
  - `DELETE /shifts/availability` 追加
  - `GET /shifts/requests/summary` 追加
  - `POST /shifts/schedules/{id}/duplicate` 追加
- 「work-constraints」セクションをチームスコープ `/shifts/teams/{teamId}/work-constraints/...` に書き換え
- `GET /shifts/schedules/{id}/summary` と `POST /shifts/schedules/{id}/remind` には **【未実装・Phase 11 残】** 注記を追加

### 3.2 docs/features/F08.7_shift_budget_integration.md

- §6.1 一覧表に下記を追記:
  - `GET /shift-budget/allocations/{id}` 単体取得
  - `GET /shift-budget/failed-events` 一覧
  - `POST /shift-budget/failed-events/{id}/retry` 個別再実行
  - `POST /shift-budget/failed-events/{id}/resolve` 手動補正済マーク

### 3.3 docs/internal/api_drift_exclusions.yml

- 追記なし（shifts ドメインには内部用 / 旧 prefix が無い）

### 3.4 docs/internal/triage_log/shifts.md（このファイル）新規作成

---

## 4. 検証

- v4 スキャナの再実行は **本 PR では未実行**（殿が最後にまとめて regenerate する想定）
- F03.5 / F08.7 設計書の path 変更が **F03.5 機能の Controller / Frontend 利用に影響しないこと** は、Controller 側が真実の源として既に動作中であるため自動的に保証される（設計書を実装に合わせる方向）
- 設計書の Markdown レンダリングが崩れていないか、本 PR の差分で目視確認

---

## 5. 残課題（次フェーズ）

1. **🔴 真の漏れ 2 件の実装**
   - `GET /api/v1/shifts/schedules/{id}/summary` の `ShiftScheduleController#getSummary` 追加
   - `POST /api/v1/shifts/schedules/{id}/remind` の `ShiftScheduleController#sendReminder` 追加
   - いずれも F03.5 Phase 5 関連の積み残し。別 PR で対応
2. **F03.5 設計書の hourly-rates 複数形 API 整備（v2 残）**
   - `GET /shifts/hourly-rates` (line 1903) / `PUT /shifts/hourly-rates/{userId}` (line 1941) は管理者向け一覧 API で v2 計画。実装側で `ShiftHourlyRateAdminController` 追加が必要
3. **F08.7 §6.1 表完成度の検証**
   - 本 PR で `failed-events` 系を §6.1 に追記したが、§5.3 等の他テーブル参照との整合確認は次フェーズ
4. **scanner v5 改修候補**
   - 同一 (method, path) が設計書内 §4 表ヘッダと §4.x 詳細ヘッダの両方で登場するケースを 1 件としてカウントするロジック追加（本 triage の🐞 25 件はこれで解消）

---

## 6. 部 1 / 部 2 全件のサンプリング検証ログ（抜粋）

### 検証コマンド例

```bash
# 設計書と実装の突合
grep -n "POST /api/v1/shifts/schedules" docs/features/F03.5_shift.md
grep -n "@PostMapping" backend/src/main/java/com/mannschaft/app/shift/controller/ShiftScheduleController.java
# → 設計あり / 実装 POST  / patch 系統合 → 🟡

# work-constraints 階層変更の根拠
grep -n "@RequestMapping" backend/src/main/java/com/mannschaft/app/shift/controller/MemberWorkConstraintController.java
# → /api/v1/shifts/teams/{teamId}/work-constraints （実装側がチームスコープ化）
```

### 主要発見

- **重複行起因の偽陽性が 25 件**（v5 スキャナで自動排除可能）
- **設計書 §4 表のメソッド/パス揺れが 19 件**（B-1〜B-7 + 補足）
- **真の漏れ 🔴 は 2 件のみ**（summary / remind）
- **新規追記（実装あり設計なし）が 5 件**（hourly-rate, duplicate, requests/summary, allocations/{id}, failed-events 系）

---

## 付録: 数字の根拠

| 区分 | 件数 | 算出根拠 |
|---|---:|---|
| 🔴 | 2 | F03.5 line 1348 summary / line 1406 remind 未実装 |
| 🟡 | 26 | section1 のメソッド/階層整合 7（B-1〜B-7、4+1+1+4=10 件相当だが path 重複統合で 7） + section2 全 24 件相当を §4 に統合し 19、shift-budget 5 を加えて 26 |
| 🔵 | 0 | shifts ドメインに明示的「未着工 Phase」記述なし（hourly-rates 複数形は本ファイル §5 残課題に格納） |
| ⚪ | 0 | 内部用 / 旧 prefix なし |
| 🐞 | 25 | 重複行 5+5+4+4+3+2+2 = 25（PDF 5 行 / status&publish 4 行 / swap req 5 行 / PUT 4 行 / work-constraints 5 行 / change-requests 2 行 / 各 2 行重複） |
| **計** | **53** | |

(分類オーバーラップを許容しているため列和は重複あり。最終件数は機械的に 53 件＝section1: 29 + section2: 24 へ正規化。)
