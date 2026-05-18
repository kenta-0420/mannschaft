# 残余ドメイン triage 統合（Stage 3 第五陣・最終陣）

> 担当: 足軽 5-δ（feature/api-drift-cleanup-misc-stage3）
> 作業日: 2026-05-17
> 入力: `docs/internal/api_drift_baseline.md` v5
> 件数規模: 28 ドメイン・約 110 件
> 位置付け: **Stage 3 のドメイン triage を完全終結させる最終陣**

---

## 1. 概要

Stage 2/3 で 22 ドメインの triage が完了したが、baseline v5 にはなお 28 ドメインが
未 triage で残っていた。本陣は **Stage 3 完全終結** を目的として、
残余ドメインを網羅的に分類する。

並列出陣中の他足軽が担当する `forms` / `succession` / `surveys` 系は
本ファイル対象外（重複回避）。

### 既存 triage 済（重複させない）

| 段階 | ドメイン |
|---|---|
| Stage 2 | admin / me / system-admin / organizations / teams / villages / users / その他総括 (`misc.md`) |
| Stage 3 第二陣 | shifts / files / timeline |
| Stage 3 第三陣 | chat / events / incidents / bulletin |
| Stage 3 第四陣 | circulation / dwelling-units / workflows / activities / safety-checks |
| Stage 3 第五陣 並列 | forms / succession / surveys |
| **本陣 (5-δ)** | **下記 28 ドメイン** |

---

## 2. サマリ（本陣担当 28 ドメイン合算）

| 分類 | 件数 |
|---:|---:|
| 🔴 真の漏れ（実装追加） | 0 |
| 🟡 設計書更新要（スコープ prefix 修正・命名揺れ・パス改名） | 約 45 |
| 🔵 将来機能（🔵 マーカ付与） | 約 30 |
| ⚪ 除外（exclusions.yml 追加） | 0（追加なし） |
| 🐞 スキャナ偽陽性（重複検出・命名揺れ等） | 約 35 |
| **合計** | **約 110** |

> **判断方針**: Stage 2 `misc.md` と同様、設計書側を実装に合わせる 🟡 方針を堅持。
> 真陽性（🔴）は 0 件であり、新規 API 実装は不要。

---

## 3. ドメイン別 triage 結果

### A. 委員会系 (F04.10)

#### 3.1 `/api/v1/committees/*` (5 件)

| メソッド | パス | 分類 | 備考 |
|---|---|---:|---|
| DELETE | `/committees/{_}` | 🟡 | 実装は `POST /committees/{_}/status` のステータス遷移で代替。設計書側を更新 |
| GET | `/committees/{_}/activity-records/{_}/pdf` | 🔵 | PDF 出力未着工 (F04.10 後続フェーズ) |
| GET | `/committees/{_}/distributions` | 🐞 | `CommitteeDistributionController` の `@GetMapping` は実装あり（スキャナ重複検出） |
| GET | `/committees/{_}/distributions/{_}/pdf` | 🔵 | PDF 出力未着工 |
| POST | `/committees/{_}/distributions` | 🐞 | 実装あり（line 547 重複） |

#### 3.2 `/api/v1/confirmable-notifications/*` (4 件)

| メソッド | パス | 分類 | 備考 |
|---|---|---:|---|
| GET | `/confirmable-notifications/confirm-by-token` | 🐞 | 設計書内重複（F04.9 line 296/394 同一） |
| GET | `/confirmable-notifications/pending` | 🐞 | 同上（F04.9 line 297/425） |
| POST | `/confirmable-notifications` | 🟡 | F04.10 のみで言及。実装は委員会経由のドメインイベントで起票される設計のため、設計書側に「内部 API」と明記して 🐞 偽陽性ではなく除外候補にすべきだが現時点 🟡 |
| POST | `/confirmable-notifications/{_}/confirm` | 🐞 | 実装あり (`ConfirmableNotificationController`) |

#### 3.3 `/api/v1/activity-records/*`, `/api/v1/circulation-documents/*`, `/api/v1/schedules` POST (F04.10 由来)

| パス | 分類 | 備考 |
|---|---:|---|
| POST `/api/v1/activity-records` | 🟡 | 委員会から「活動記録」を作成する記述だが、実装は `/api/v1/committees/{_}/activity-records` (Minutes Controller) のスコープ付き |
| POST `/api/v1/circulation-documents` | 🟡 | 同上、`/api/v1/circulation` 経由が実装。F04.10 設計書側を修正 |
| POST `/api/v1/schedules` | 🟡 | 同上、`/api/v1/{scopeType}/{scopeId}/schedules` がスコープ付き実装。F04.10 設計書側を修正 |

### B. コンタクト系 (F04.8)

#### 3.4 `/api/v1/contacts/*` `/contact-requests/*` `/contact-invite-tokens/*` `/contact-request-blocks/*`

| パス | 分類 | 備考 |
|---|---:|---|
| GET `/contacts` | 🐞 | `ContactController` で実装あり（一致） |
| POST `/contact-requests` (3 重複) | 🐞 | `ContactRequestController` で実装あり |
| GET / POST `/contact-invite-tokens` | 🐞 | `ContactInviteTokenController` で実装あり |
| GET / POST `/contact-request-blocks` | 🐞 | `ContactRequestBlockController` で実装あり |

→ 全件 D-1 / D-3 スキャナ偽陽性（同一エンドポイントの設計書内・複数フェーズ間重複）。
真の漏れ 0 件。

### C. ソーシャル系 (F04.4 / F04.1)

#### 3.5 `/api/v1/follows/*` (5 件)

| メソッド | パス | 分類 | 備考 |
|---|---|---:|---|
| POST | `/follows` | 🟡 | 実装は `/api/v1/social/follows` (F04.4 リネーム後)。Stage 2 `misc.md` C 節「F04.4 系」に登録済み |
| DELETE | `/follows/{_}/{_}` | 🟡 | 同上 |
| GET | `/follows/check` | 🟡 | 同上 |
| GET | `/follows/followers` | 🟡 | 同上 |
| GET | `/follows/following` | 🟡 | 同上 |

#### 3.6 `/api/v1/mutes/*` (3 件)

| メソッド | パス | 分類 | 備考 |
|---|---|---:|---|
| GET | `/mutes` | 🟡 | 実装は `/api/v1/timeline/mutes` (TimelineMuteController)。F04.1 設計書のパスを修正 |
| POST | `/mutes` | 🟡 | 同上 |
| DELETE | `/mutes/{_}/{_}` | 🟡 | 同上 |

### D. メンバーシップ系 (F00.5)

#### 3.7 `/api/v1/memberships/*` (3 件) / `/api/v1/member-positions/*` (1 件)

| パス | 分類 | 備考 |
|---|---:|---|
| POST `/memberships` | 🔵 | F00.5 Phase 4 未完。実装は `/api/v1/villages/{villageId}/memberships` のみ。本来「scope-agnostic 統一 API」として設計されているが、teams/orgs 配下の実装はまだ |
| POST `/memberships/{_}/leave` | 🔵 | 同上 |
| POST `/memberships/{_}/positions` | 🔵 | 同上 |
| POST `/member-positions/{_}/end` | 🔵 | 同上 |

→ F00.5 §7.x の設計は Phase 4 完了前提。実装側は villages のみ追従。
**設計書側に「Phase 4 完了後の正式エンドポイント」と注記を入れる（🔵 マーカ）か、
scope-prefix-less ルーティングへの統一実装 PR が必要。本陣は記録のみ。**

### E. ブログ系 (F06.1)

#### 3.8 `/api/v1/blog/*` (4 件)

| メソッド | パス | 分類 | 備考 |
|---|---|---:|---|
| GET | `/blog/tags` | 🐞 | `BlogTagController` で実装あり（一致） |
| POST | `/blog/tags` | 🐞 | 同上（line 371/694/1038 重複） |
| GET | `/blog/series` | 🐞 | `BlogSeriesController` で実装あり |
| POST | `/blog/series` | 🐞 | 同上 |

→ 全件 D-1 スキャナ偽陽性。

#### 3.9 `/api/v1/blog-posts/*` (1 件)

| メソッド | パス | 分類 | 備考 |
|---|---|---:|---|
| GET | `/blog-posts` | 🟡 | F02.5 設計書記載だが、実装は `/api/v1/blog/posts/*` (BlogPostController)。設計書側のパス命名を修正 |

### F. ジョブマッチング系 (F13.1)

> F13.1 短期求人マッチング Phase 5 未着工分。Stage 2 `misc.md` §B でも 🔵 マーカ済みのドメイン群。
> 本陣で改めて整理して記録する。

#### 3.10 `/api/v1/jobs/*` (9 件)

| メソッド | パス | 分類 | 備考 |
|---|---|---:|---|
| GET | `/jobs` | 🐞 | `JobPostingController @GetMapping` で実装あり |
| GET | `/jobs/public-board` | 🟡 | JobPostingController に `public-board` 専用エンドポイント未実装。`/jobs` (一覧) で公開ボードクエリ対応の可能性。設計書精査必要 → 🔵 |
| POST | `/jobs` | 🐞 | 実装あり |
| POST | `/jobs/check-ins` | 🐞 | `JobCheckInController` で実装あり (line 116/1633/1766/2487 重複) |
| POST | `/jobs/fee-preview` | 🟡 | 実装は `@GetMapping("/fee-preview")` (GET)。設計書 POST と方法不一致 |
| POST | `/jobs/{_}/applications` | 🔵 | `JobApplicationController` 不存在。Phase 5 未着工 |
| DELETE | `/jobs/{_}/applications/me` | 🔵 | 同上 |
| POST | `/jobs/{_}/applications/{_}/accept` | 🔵 | 同上 |
| POST | `/jobs/{_}/applications/{_}/reject` | 🔵 | 同上 |

#### 3.11 `/api/v1/job-contracts/*` (17 件・本陣最大)

全 17 件、実装側 controller `JobContractController` が存在しない。F13.1 Phase 5 未着工。

| 分類 | 件数 |
|---:|---:|
| 🔵 (Phase 5 未着工) | 17 件全件 |

主なエンドポイント（全件 F13.1_short_term_job_matching.md より）:

- GET `/job-contracts` / `/{_}` (検索・詳細)
- POST `/{_}/approve` / `/cancel` / `/start` / `/report-completion` / `/reject-completion`
- POST `/{_}/qr-tokens` / `/{_}/qr-tokens/current`
- POST `/{_}/reviews` / `/{_}/disputes` / `/admin-override-checkin`
- POST `/{_}/time-confirmations` （+ approve/dispute）

#### 3.12 `/api/v1/job-payments/*` (3 件)

| メソッド | パス | 分類 |
|---|---|---:|
| GET | `/job-payments/{_}/escrow-status` | 🔵 |
| POST | `/job-payments/{_}/dispute` | 🔵 |
| POST | `/job-payments/{_}/early-release` | 🔵 |

#### 3.13 `/api/v1/job-disputes/*` (1 件) / `/api/v1/jobber-invitations/*` (2 件)

| パス | 分類 |
|---|---:|
| POST `/job-disputes/{_}/resolve` | 🔵 |
| POST `/jobber-invitations/{_}/accept` | 🔵 |
| POST `/jobber-invitations/{_}/decline` | 🔵 |

#### 3.14 `/api/v1/no-show-records/*` (2 件)

F03.11 由来。

| パス | 分類 |
|---|---:|
| POST `/no-show-records/{_}/dispute` | 🔵 |
| POST `/no-show-records/{_}/dispute/resolve` | 🔵 |

→ F13.1 / F03.11 系合算で **🔵 約 30 件** が Phase 5 未着工として記録される。
当該設計書には既に Phase 5 マーカが入っているが、本足軽からは設計書編集はせず、
将来 F13.1 Phase 5 着工 PR で本 triage_log を参照する想定とする。

### G. 予算系 (F08.6)

#### 3.15 `/api/v1/budget/*` (2 件)

| メソッド | パス | 分類 | 備考 |
|---|---|---:|---|
| GET | `/budget/fiscal-years/{_}/allocations` | 🐞 | `BudgetAllocationController` で実装あり（一致） |
| PUT | `/budget/fiscal-years/{_}/allocations` | 🐞 | 同上 |

→ 設計書内重複検出（D-1）。真の漏れ 0 件。

### H. LINE 連携 (F09.4)

#### 3.16 `/api/v1/line/*` (11 件)

| パス | 実装側 | 分類 |
|---|---|---:|
| GET / POST / PUT / DELETE `/line/configs[/...]` | `/api/v1/teams/{teamId}/line/config` + `/api/v1/organizations/{orgId}/line/config` (LineBotConfigController) | 🟡 |
| POST `/line/configs/{_}/test` | `/api/v1/teams/{teamId}/line/test` 等 | 🟡 |
| GET `/line/configs/{_}/logs` | `/api/v1/teams/{teamId}/line/logs` 等 | 🟡 |
| POST `/line/configs/{_}/broadcast` | 未実装 | 🔵 |
| POST `/line/link` | `/api/v1/users/me/line/link` (UserLineController) | 🟡 |
| DELETE `/line/link/{_}` | 同上 | 🟡 |
| GET `/line/link/status` | `/api/v1/users/me/line/status` | 🟡 |

→ 全 11 件、設計書 F09.4 が古い `/api/v1/line/*` フラット URL で記述されているが、
実装は **スコープ付き** (`teams/{teamId}/line/...`) + **ユーザー自分用** (`users/me/line/...`)
に分割されている。**設計書側を実装に合わせて修正する 🟡 大規模対応が必要**。

### I. ギャラリー (F06.2)

#### 3.17 `/api/v1/gallery/*` (2 件)

| メソッド | パス | 分類 | 備考 |
|---|---|---:|---|
| GET | `/gallery/albums` | 🐞 | `PhotoAlbumController` で実装あり（一致） |
| POST | `/gallery/albums` | 🐞 | 同上 |

→ 設計書内重複検出（D-1）。

### J. ダッシュボード / モジュール / マイ

#### 3.18 `/api/v1/dashboard/*` (1 件)

| パス | 分類 |
|---|---:|
| GET `/dashboard` | 🐞 |

→ `DashboardController @GetMapping` で実装あり。F02.2 / F02.10 で複数記載 (D-3)。

#### 3.19 `/api/v1/modules/*` (1 件)

| パス | 分類 |
|---|---:|
| GET `/modules` | 🐞 |

→ `ModuleController` で実装あり（line 1059/1060 が設計書内重複）。

#### 3.20 `/api/v1/my/*` (1 件)

| パス | 分類 |
|---|---:|
| GET `/my/receipts` | 🐞 |

→ `ReceiptMyController` で実装あり（line 1077/1078 重複）。

### K. その他

#### 3.21 `/api/v1/attendance/*` (2 件)

| メソッド | パス | 分類 | 備考 |
|---|---|---:|---|
| POST | `/attendance/requirements/evaluations/{_}/disclose` | 🟡 | 実装は `/api/v1/teams/{teamId}/attendance/requirements/evaluations/{_}/disclose` (AttendanceDisclosureController)。設計書側スコープ抜き |
| POST | `/attendance/requirements/evaluations/{_}/withhold` | 🟡 | 同上 |

#### 3.22 `/api/v1/disclosure-templates/*` (1 件)

| パス | 分類 |
|---|---:|
| GET `/disclosure-templates` | 🐞 |

→ `DisclosureFormTemplateController` で実装あり。F09.14 設計書内重複。

#### 3.23 `/api/v1/families/*` (1 件)

| パス | 分類 |
|---|---:|
| GET `/families/{_}/members/{_}/personal-timetables` | 🐞 |

→ `FamilyPersonalTimetableController` で実装あり（一致）。

#### 3.24 `/api/v1/feature-flags/*` (1 件)

| メソッド | パス | 分類 | 備考 |
|---|---|---:|---|
| GET | `/feature-flags/me` | 🔵 | `/api/v1/system-admin/feature-flags` (システム管理者用) は実装あり、ユーザー自分用エンドポイントは F12.2 で未着工。3 重複は D-1 偽陽性混在 |

#### 3.25 `/api/v1/feedback/*` (4 件)

| パス | 分類 | 備考 |
|---|---:|---|
| GET `/feedback` | 🟡 | 実装は `/api/v1/feedbacks` (s あり; FeedbackController)。設計書側を `feedbacks` にリネーム |
| POST `/feedback` | 🟡 | 同上 |
| POST `/feedback/{_}/vote` | 🟡 | 実装は `/api/v1/feedbacks/{_}/votes` (複数形)。設計書側のパス整合性修正 |
| DELETE `/feedback/{_}/vote` | 🟡 | 同上 |

→ Stage 2 `misc.md` C 節で `/api/v1/feedbacks/**` が 🟡 候補として記録済みだが、本陣で改めて精査の結果、**実装が複数形 `feedbacks` で確定**しているため、設計書側を実装に合わせる方針が確定。

#### 3.26 `/api/v1/kb/*` (1 件)

| パス | 分類 |
|---|---:|
| GET `/kb/pages/{_}` | 🔵 |

→ F11.2 多言語コンテンツの knowledge base 機能。実装ファイル不存在 (Phase 未着工)。

#### 3.27 `/api/v1/schedules/*` (2 件) — 部分 triage 済 / 第二陣との重複確認

| パス | 分類 | 備考 |
|---|---:|---|
| GET `/schedules/{_}/media` | 🐞 | `ScheduleMediaController` で実装あり（line 1468/1469 重複） |
| POST `/schedules` | 🟡 | F04.10 設計書の組織コンテキスト省略記述。実装は `/api/v1/organizations/{_}/schedules`・`/api/v1/teams/{_}/schedules` |

---

## 4. 実機改修コミット範囲

本足軽がコミットする変更は次のとおり。

### 4.1 triage_log 新規作成

- `docs/internal/triage_log/misc-stage3.md` — 本ファイル

### 4.2 exclusions.yml への追記

**追加なし。** 既に Stage 2 で追加済みのパターンで網羅できている。
本陣担当ドメインで新規除外候補となる「内部 API」「外部 webhook」「自己内省」系は
出現しなかった。

### 4.3 設計書への注記追加（着手範囲を限定）

本陣で大規模な設計書編集はせず、後続足軽の参考用に「**残課題 §6**」に
すべての 🟡 / 🔵 を集約して記録する。

実機改修は他足軽の並列出陣との衝突を避けるため最小限とする。

---

## 5. 難しい事例

### 事例 1: F00.5 membership_basis の Phase 4 完了前の状態

設計書 F00.5 では `/api/v1/memberships` を **scope-agnostic 統一 API** として
詳細に定義しているが、現時点で実装側は `/api/v1/villages/{villageId}/memberships`
のみ。F00.5 §7.x ローテーション・Phase 4 完了予定の文脈を読むと、teams/orgs
配下にも同じ controller を移植する設計だが、未着工。

判断:
- **🔴 真陽性とはしない**。Phase 4 未完を理由とする 🔵 が妥当。
- 設計書側に「Phase 4 で実装」マーカを足軽が入れるべきだが、**F00 配下は他軍が並列対応中**
  のため、編集は本陣では行わず、後続足軽 PR で対応。

### 事例 2: F13.1 短期求人マッチング Phase 5 一括 🔵

job-contracts 17 件・job-payments 3 件・job-disputes 1 件・jobber-invitations 2 件・
no-show-records 2 件 = **計 25 件** が全件 🔵 Phase 5 未着工。

判断:
- Stage 2 `misc.md` §B でも既に 🔵 として記録済み。
- 本陣で重複再確認した結果、**新規の判断変更は無し**。F13.1 Phase 5 着工 PR で
  本 triage_log を読んで 🔵 → ✅ に更新する想定。

### 事例 3: F09.4 LINE 11 件のスコープ prefix リファクタ追随漏れ

設計書 F09.4 は `/api/v1/line/configs/*` フラット URL で書かれているが、
実装は次の 3 系統に分散している:

1. `/api/v1/teams/{teamId}/line/config` (LineBotConfigController)
2. `/api/v1/organizations/{orgId}/line/config` (LineBotConfigController)
3. `/api/v1/users/me/line/{link,status}` (UserLineController)

設計書 F09.4 はリファクタ後の URL に追随していない。
**修正規模が大きい**（11 件以上のテーブル書き換え）ため、F09.4 ドメイン担当
足軽 PR に委譲する。本陣は記録のみ。

### 事例 4: 委員会 DELETE エンドポイントの設計と実装の不一致

設計書 F04.10 は `DELETE /api/v1/committees/{committeeId}` を記載しているが、
実装は `POST /api/v1/committees/{committeeId}/status` で「ステータス遷移」
(active → archived) の形で「論理削除」する設計。

判断:
- 🟡 設計書側を修正（DELETE → POST /status に統一）
- もしくは、実装に DELETE エイリアスを追加する（後方互換）
- 本陣は記録のみとし、F04.10 担当足軽 PR で対応

---

## 6. 残課題（引き継ぎ）

後続足軽が個別ドメイン PR で対応すべき項目を以下に列挙する。

### 6.1 設計書編集 🟡 系（実装に追随）

1. **F02.5 action-memo**: `/api/v1/blog-posts` を `/api/v1/blog/posts` にリネーム
2. **F03.11 recruitment_listing** / **F03.13 school_daily_subject_attendance**:
   `/api/v1/attendance/...` を `/api/v1/teams/{teamId}/attendance/...` にスコープ追加
3. **F04.1 timeline**: `/api/v1/mutes/*` を `/api/v1/timeline/mutes` に修正
4. **F04.4 social_profiles**: `/api/v1/follows/*` 5 件を `/api/v1/social/follows` に統一
   （Stage 2 `misc.md` 残課題 §5 と重複・統合対応）
5. **F04.10 committee**:
   - `DELETE /api/v1/committees/{_}` を POST status 遷移に修正
   - `POST /api/v1/schedules` / `/activity-records` / `/circulation-documents` を
     スコープ付きパスに修正
6. **F09.4 line_sns**: `/api/v1/line/configs/*` 11 件を
   `/api/v1/{teams|organizations}/{id}/line/config` + `/api/v1/users/me/line/*` に再構成
7. **F10.1 admin_dashboard**: `/api/v1/feedback/*` を `/api/v1/feedbacks/*` (複数形) に修正

### 6.2 🔵 将来機能マーカ付与（実装は後続フェーズ）

1. **F00.5 membership_basis Phase 4**:
   `/api/v1/memberships/*` 3 件 + `/api/v1/member-positions/{_}/end` 1 件
2. **F04.10 committee**:
   `/committees/{_}/activity-records/{_}/pdf` / `/distributions/{_}/pdf` (PDF 出力フェーズ)
3. **F09.4 line_sns**:
   `/api/v1/line/configs/{_}/broadcast` (broadcast 機能 Phase 未着工)
4. **F11.2 multilingual_content / kb**:
   `/api/v1/kb/pages/{_}` (knowledge base Phase 未着工)
5. **F12.2 feature_flag**:
   `/api/v1/feature-flags/me` (ユーザー自分用 Phase 未着工)
6. **F13.1 short_term_job_matching Phase 5 一括** (約 25 件、上記 §3.10〜3.14 参照)

### 6.3 スキャナ v6 改修候補（D-1 / D-3 系）

本陣の triage で改めて確認できた 🐞 偽陽性の根本原因:

- **D-1 (同一設計書内の (method, path) 重複)** が依然として 🐞 大量発生中
  - 例: contacts / contact-requests / contact-invite-tokens / contact-request-blocks
        全件が 1〜3 重複で検出
  - 設計書フォーマットで「§表」と「§シーケンス例」で同じパスを 2 度書く慣習が原因
  - v6 で「設計書内同一 (method, path) を 1 件に集約」する正規化が望まれる
- **D-3 (複数設計書ファイル間の (method, path) 重複)** も発生中
  - 例: dashboard / modules / blog-posts などが複数フェーズ設計書で言及

これらが v6 で根治すれば、本陣担当 110 件のうち **約 35 件の 🐞** が自動解消する。

---

## 7. 検証

- [x] triage_log/misc-stage3.md 作成完了
- [x] exclusions.yml の検査 — 追加不要を確認
- [x] 28 ドメインの実装側 controller を grep で網羅確認
- [x] 既存 triage_log と重複しないことを確認
- [x] Stage 2/3 既往足軽（misc / admin / shifts / files / timeline / chat / events /
  incidents / bulletin / circulation / dwelling-units / workflows / activities /
  safety-checks / forms / succession / surveys）と境界を明確化

## 8. Stage 3 完全終結宣言

本陣（足軽 5-δ）の commit により、baseline v5 における **すべてのドメインに対する
triage が完了** する。

| 集計 | 件数 |
|---|---:|
| Stage 2 misc 合算 | 約 990 |
| Stage 3 各陣 合算（第二〜第四陣 + 並列第五陣 forms/succession/surveys） | 約 280 |
| 本陣 5-δ（残余 28 ドメイン） | 約 110 |
| **総 triage 件数** | **約 1380** |

🟢 **Stage 3 ドメイン triage 完全終結**。
次フェーズ（Stage 4 想定）は、本 triage 結果に基づいて
**設計書側 🟡 修正 PR + 🔵 マーカ付与 PR + スキャナ v6 D-1/D-3 根治** を
ドメイン別に分割して実施する。
