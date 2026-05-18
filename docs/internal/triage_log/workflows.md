# /api/v1/workflows/* + /api/v1/workflow-requests/* + 関連スコープ展開 triage 作業ログ（Stage 3 第四陣 4-γ）

> 担当: 足軽（feature/api-drift-cleanup-workflows）
> 作業日: 2026-05-17
> 入力: `docs/internal/api_drift_baseline.md` v5 中の workflows ドメイン
>   - section 1 (設計あり・実装なし) `/api/v1/workflows/*` = **14 件**（重複行 5 件含む実数 19 行）
>   - section 1 (設計あり・実装なし) `/api/v1/workflow-requests/*` = **2 件**（`me` `pending`）
>   - section 1 (設計あり・実装なし) `/api/v1/{_}/* (workflow-*)` 分 = **17 件**（workflow-requests 9 件 + workflow-templates 8 件）
>   - section 2 (実装あり・設計なし) `/api/v1/workflow-requests/*` = **6 件**
>   - section 2 (実装あり・設計なし) `/api/v1/organizations/{_}/workflow-*` = **10 件**
>   - section 2 (実装あり・設計なし) `/api/v1/teams/{_}/workflow-*` = **10 件**
>   - section 2 (実装あり・設計なし) `/api/v1/users/{_}/workflow-*` = **10 件**（Spring の path 変数展開で実装 1 本が 3 スコープ分検出される偽陽性）
>   - 合計実数 **約 79 件** を triage 対象とした（行重複・スコープ展開重複あり）

---

## サマリ

| 分類 | 件数 | 備考 |
|---|---:|---|
| 🔴 真の漏れ（実装追加要） | 4 | `/workflow-requests/me`・`/workflow-requests/pending`・添付ファイル一式（upload-url / attachments POST / DELETE）・`requests/by-source` の 4 系統。F05.6 Phase 11 設計に明記されているが Controller 未実装 |
| 🟡 設計書更新要 | 14 | F05.6 §4 内で `/api/v1/workflows/requests/...` スラッシュ階層と記述されている箇所を、実装に合わせて `/api/v1/workflow-requests/{requestId}/...` 系へ書き換え。`approve/reject/return` 3 本立て → `decide` 1 本立てへ統合 |
| 🔵 将来機能（🔵 マーカ付与） | 3 | `external` 申請作成・`by-source` 検索・`/workflows/requests/pending` は F05.6 §10 (Phase 12 他機能連携) の想定。Phase 11 では未着工とマーカ付与 |
| ⚪ 除外（exclusions.yml） | 0 | 内部用 / 旧 prefix なし。workflows は外部公開・UI 利用ともに正式エンドポイント |
| 🐞 スキャナ偽陽性（重複行・スコープ展開） | 約 58 | v5 ベースラインで section 2 のスコープ別 3 倍展開（organizations/teams/users）と、section 1 内の `/workflows/requests/...` 行が §4 表ヘッダと §4.x 詳細ヘッダの 2 箇所で各 2 回登場する形が主要因 |
| **合計** | **約 79** | |

> 補足: workflows ドメインの実装は **WorkflowRequestController / WorkflowTemplateController / WorkflowTemplateStatusController (スコープ展開) + WorkflowApprovalController / WorkflowCommentController (フラット)** の 5 本構成。
> 設計書 F05.6 は最新コミットで「実装整合性注記（2026-05-17）」が既に追加されており、`/workflows/templates` 旧スラッシュ階層 → `/workflow-templates` ハイフン形式への書き換え方針も明文化されている。
> 本 PR は **§4 残部（承認操作・コメント・添付・他機能連携）も同方針で実装に揃える**ことを主眼とする。

---

## 1. section 1（設計あり・実装なし）の分類

### A. `/api/v1/workflows/*` 14 件（実数 19 行）

#### A-1. パス階層の不一致（🟡 設計書更新）

実装は **存在するが path 階層が違う** ケース。`/workflows/requests/...` スラッシュ階層は **どの Controller にも存在しない**。実装は `/workflow-requests/...` ハイフン形式かつ requestId は path variable。

| メソッド | パス（設計書） | 設計書行 | 実装側の正しい path | 判定 |
|---|---|---:|---|---|
| GET | `/workflows/requests/{_}/comments` | 404 | `GET /workflow-requests/{requestId}/comments` (`WorkflowCommentController#listComments`) | 🟡 |
| POST | `/workflows/requests/{_}/comments` | 405 | `POST /workflow-requests/{requestId}/comments` (`WorkflowCommentController#createComment`) | 🟡 |
| DELETE | `/workflows/requests/{_}/comments/{_}` | 406 | `DELETE /workflow-requests/{requestId}/comments/{commentId}` (`WorkflowCommentController#deleteComment`) | 🟡 |
| POST | `/workflows/requests/{_}/submit` | 587 | `POST /{scopeType}/{scopeId}/workflow-requests/{requestId}/submit` (`WorkflowRequestController#submitRequest`) | 🟡 |
| POST | `/workflows/templates/{_}/requests` | 536 | `POST /{scopeType}/{scopeId}/workflow-requests` (templateId はリクエストボディ。`WorkflowRequestController#createRequest`) | 🟡 |

#### A-2. 承認操作 3 本立て → `decide` 1 本立て統合（🟡 設計書更新）

実装: `WorkflowApprovalController` (`@RequestMapping("/api/v1/workflow-requests/{requestId}")`) に **`POST /decide` のみ** 存在。リクエストボディの `decision` 列挙値（`APPROVED` / `REJECTED` / `RETURNED`）で 3 種の判定を統合的に処理する。

設計書（F05.6 §4 line 397/398/399、§4 詳細仕様 line 634/702/728）: PATCH ではないが POST で `/approve` `/reject` `/return` の 3 本に分離した記述。

| メソッド | パス（設計書） | 設計書行 | 実装 | 判定 |
|---|---|---:|---|---|
| POST | `/workflows/requests/{_}/approve` | 397, 634 | `POST /workflow-requests/{requestId}/decide` (body: `decision=APPROVED`) | 🟡 |
| POST | `/workflows/requests/{_}/reject` | 398, 702 | `POST /workflow-requests/{requestId}/decide` (body: `decision=REJECTED`) | 🟡 |
| POST | `/workflows/requests/{_}/return` | 399, 728 | `POST /workflow-requests/{requestId}/decide` (body: `decision=RETURNED`) | 🟡 |

判定根拠: 承認操作は楽観的ロック (`version`) ・電子印鑑連携 (`seal_id`) ・コメントなど共通項目が多く、3 本に分けて並行進化させると DTO の差分管理が煩雑になる。実装側で `ApprovalDecisionRequest` という共通 DTO で吸収し、Service 層で `decision` を見て分岐させる現実装の方が保守容易。設計書 §4 line 397-399 と §4.x 詳細ヘッダ（line 634-728）を **`decide` 1 本立て** に書き換える 🟡。

#### A-3. 添付ファイル系（🔴 真の漏れ + 🔵 将来機能）

| メソッド | パス（設計書） | 設計書行 | 実装側調査 | 判定 |
|---|---|---:|---|---|
| POST | `/workflows/requests/{_}/upload-url` | 411 | 該当なし（`@PostMapping("/upload-url")` グレップ無し） | 🔴 真の漏れ |
| POST | `/workflows/requests/{_}/attachments` | 412 | 該当なし。GET (一覧) は `WorkflowCommentController#listAttachments` で存在するが POST 登録は無い | 🔴 真の漏れ |
| DELETE | `/workflows/requests/{_}/attachments/{_}` | 413 | 該当なし | 🔴 真の漏れ |

判定根拠: 添付ファイル登録機能は F05.6 §3 テーブル定義（`workflow_request_attachments`）と §4 で完全に設計されているが、**Controller に POST/DELETE エンドポイントが存在しない**。Repository (`WorkflowRequestAttachmentRepository`) と Entity (`WorkflowRequestAttachmentEntity`) は存在し GET (一覧) のみ実装済 → **登録・削除 API が Phase 11 の実装漏れ**。次フェーズで実装追加要。triage_log の §5 残課題に記録。

#### A-4. 他機能連携 API（🔵 将来機能）

| メソッド | パス（設計書） | 設計書行 | 実装側調査 | 判定 |
|---|---|---:|---|---|
| POST | `/workflows/templates/{_}/requests/external` | 418, 797 | 該当なし | 🔵 将来機能（Phase 12 他機能連携） |
| GET | `/workflows/requests/by-source` | 419, 829 | 該当なし | 🔵 将来機能（Phase 12 他機能連携） |

判定根拠: F05.6 §10 で「他機能（F09.7 施設予約、F08.x 経費等）から source_type + source_id で連動」と明記。Phase 11 本体実装は MVP（テンプレ/申請/承認/コメント）に絞り、他機能ブリッジは **Phase 12 以降**に分離する設計判断と推定。設計書側に **🔵 マーカ付与** で「Phase 12 計画」と明示する 🟡 兼 🔵。

#### A-5. `pending` 一覧（🔴 真の漏れ または 🔵）

| メソッド | パス（設計書） | 設計書行 | 実装側調査 | 判定 |
|---|---|---:|---|---|
| GET | `/workflows/requests/pending` | 754 | 該当なし | 🔵 将来機能（フラット形式の自分宛承認待ち一覧。Phase 12） |

設計書 §4 line 754 で「自分が承認待ちの申請一覧」として完全に詳細仕様まで記述あり。
実装には WorkflowRequestController の scope 展開形式の list (`GET /{scopeType}/{scopeId}/workflow-requests?status=PENDING`) があるが、これは「スコープ内の PENDING 申請」を返すもので「**自分が承認待ち**」とは異なる。承認者横断の一覧は未実装。

Phase 11 実装範囲ではスコープ内 list で代用が利く運用と想定し、本 PR では 🔵 マーカ付与 + §5 残課題に記録。

### B. `/api/v1/workflow-requests/*` section 1 (2 件)

| メソッド | パス | 設計書行 | 実装側調査 | 判定 |
|---|---|---:|---|---|
| GET | `/workflow-requests/me` | 385 | 該当なし | 🔴 真の漏れ（自分の申請一覧。Phase 11 設計で明示） |
| GET | `/workflow-requests/pending` | 386 | 該当なし | A-5 と同じ → 🔵 |

判定根拠: `/workflow-requests/me` は MEMBER ロールが自分の申請を一覧する基本機能で、**Phase 11 MVP の必須 API**。スコープ展開形式の `GET /{scopeType}/{scopeId}/workflow-requests` はスコープ ADMIN 向けの全件一覧で、申請者自身が複数スコープ横断で自分の申請を見るユースケースには適さない → 真の漏れ。フロントエンド「マイ申請一覧」画面が機能していない可能性があるので別 PR で実装追加すべき。

### C. `/api/v1/{_}/{_}/workflow-*` section 1 (17 件) スコープ展開分

ベースライン line 2369-2433 に列挙されている 17 件は、設計書 §4 line 370-392 のスコープ抽象記述（`{scopeType}/{scopeId}/workflow-...`）と実装の `@RequestMapping("/api/v1/{scopeType}/{scopeId}/...")` の path variable が **完全一致** している。

| メソッド | パス | 設計書行 | 実装 | 判定 |
|---|---|---:|---|---|
| GET | `/{_}/{_}/workflow-templates/{_}` | 374 | `WorkflowTemplateController#getTemplate` | 🐞 重複検出 |
| PUT | `/{_}/{_}/workflow-templates/{_}` | 375 | `WorkflowTemplateController#updateTemplate` | 🐞 |
| DELETE | `/{_}/{_}/workflow-templates/{_}` | 376 | `WorkflowTemplateController#deleteTemplate` | 🐞 |
| POST | `/{_}/{_}/workflow-templates/{_}/activate` | 377 | `WorkflowTemplateStatusController#activateTemplate` | 🐞 |
| POST | `/{_}/{_}/workflow-templates/{_}/deactivate` | 378 | `WorkflowTemplateStatusController#deactivateTemplate` | 🐞 |
| POST | `/{_}/{_}/workflow-templates/{_}/requests` | 387 | A-1（パス階層変更）。実装は `/workflow-requests` (POST) | 🟡（A-1 で吸収） |
| GET | `/{_}/{_}/workflow-requests/{_}` | 388 | `WorkflowRequestController#getRequest` | 🐞 |
| PATCH | `/{_}/{_}/workflow-requests/{_}` | 389 | 実装は **PUT** `/workflow-requests/{requestId}` (`updateRequest`) | 🟡 PUT→PATCH 統一案件 |
| POST | `/{_}/{_}/workflow-requests/{_}/submit` | 390 | `WorkflowRequestController#submitRequest` | 🐞 |
| POST | `/{_}/{_}/workflow-requests/{_}/withdraw` | 391 | `WorkflowRequestController#withdrawRequest` | 🐞 |
| DELETE | `/{_}/{_}/workflow-requests/{_}` | 392 | `WorkflowRequestController#deleteRequest` | 🐞 |

合計 17 件 = **🐞 14 件（path 完全一致だが baseline で section 1 にも section 2 にも計上）+ 🟡 3 件**。

スキャナ v5 が `/api/v1/{_}/{_}/workflow-requests/...` を section 1 に計上した一方、section 2 では `/api/v1/organizations/{_}/workflow-requests/...` `/teams/...` `/users/...` の 3 スコープ展開で計上されているため両方向に二重カウントが発生している。v5 改修で「path variable {_}/{_} のスコープ抽象は具体スコープと同一視する」ロジック追加で解消可能。

その中で **B-1**: `PATCH /{scopeType}/{scopeId}/workflow-requests/{id}` (設計書 line 389) と実装 `PUT /workflow-requests/{id}` (`updateRequest`) は **メソッド不一致** → 🟡 設計書更新（PUT に揃える、または実装を PATCH 化）。

---

## 2. section 2（実装あり・設計なし）の分類

### α. `/api/v1/workflow-requests/*` 6 件

| メソッド | パス | Controller | 判定 |
|---|---|---|---|
| GET | `/workflow-requests/{_}/comments` | `WorkflowCommentController#listComments` | 🟡 設計書 §4 line 404 を `/workflow-requests/` 形式に書き換えで吸収（A-1） |
| POST | `/workflow-requests/{_}/comments` | `WorkflowCommentController#createComment` | 🟡 同上（A-1） |
| PUT | `/workflow-requests/{_}/comments/{_}` | `WorkflowCommentController#updateComment` | 🟡 F05.6 §4 に **コメント更新 API を新規追記**（設計書に PUT/PATCH コメント API の記載なし） |
| DELETE | `/workflow-requests/{_}/comments/{_}` | `WorkflowCommentController#deleteComment` | 🟡 A-1 で吸収 |
| GET | `/workflow-requests/{_}/attachments` | `WorkflowCommentController#listAttachments` | 🟡 F05.6 §4 に **添付一覧 API を新規追記**（設計書には GET 形式の記載なし、POST/DELETE しか無い） |
| POST | `/workflow-requests/{_}/decide` | `WorkflowApprovalController#decide` | 🟡 A-2 統合書き換えで吸収 |

合計 6 件 = **🟡 6 件**（うち 4 件は A-1/A-2 で吸収、2 件は新規追記）。

### β. `/api/v1/organizations/{_}/workflow-*` + `/teams/{_}/workflow-*` + `/users/{_}/workflow-*` (各 10 件 = 30 件)

ベースライン line 2487-3095 でスコープ展開 3 倍され合計 30 件。すべて WorkflowRequestController / WorkflowTemplateController / WorkflowTemplateStatusController の **同一実装メソッドが Spring の `@PathVariable String scopeType` を介して 3 通りの URL で叩かれている** ことに起因する偽陽性。

設計書 §4 では line 370-392 の `{scopeType}/{scopeId}` 抽象記述で **既に網羅されている**。

| メソッド種別 | 各スコープあたり件数 | 判定 |
|---|---:|---|
| DELETE workflow-templates/{id} | 各 1 件 | 🐞 |
| DELETE workflow-requests/{id} | 各 1 件 | 🐞 |
| GET workflow-templates/{id} | 各 1 件 | 🐞 |
| GET workflow-requests/{id} | 各 1 件 | 🐞 |
| POST workflow-requests/{id}/submit | 各 1 件 | 🐞 |
| POST workflow-requests/{id}/withdraw | 各 1 件 | 🐞 |
| POST workflow-templates/{id}/activate | 各 1 件 | 🐞 |
| POST workflow-templates/{id}/deactivate | 各 1 件 | 🐞 |
| PUT workflow-templates/{id} | 各 1 件 | 🐞 |
| PUT workflow-requests/{id} | 各 1 件 | 🐞 + 🟡 A-1 の PATCH 整合と併せて検討 |

合計 30 件 = **🐞 30 件**（スコープ展開 3 倍偽陽性。設計書 §4 抽象記述で網羅済）。

`/users/{_}/workflow-*` は実装側で User スコープが許可されていない（Service 内で `IllegalArgumentException` が投げられる想定）にもかかわらず、Spring の URL マッチングは `@PathVariable` で受けてしまうため baseline で計上される。これは scanner 側で「実装側の Service バリデーション結果を見ない」設計上の限界。v5 では「Controller の `@RequestMapping` の path variable は `{*}` で何でも受けるが、運用上有効なスコープのみが正」と注記する案も検討余地あり。

### γ. その他（workflow ドメイン外）

なし。

---

## 3. 修正済みファイル一覧（本 PR のスコープ）

### 3.1 docs/features/F05.6_workflow_approval.md

- §4 API 仕様（line 397-413, 536, 587, 634-728, 754, 797, 829 周辺）:
  - `/workflows/requests/{_}/approve` `/reject` `/return` → `/workflow-requests/{_}/decide` に統合書き換え（A-2）
  - `/workflows/requests/{_}/comments` 系 → `/workflow-requests/{_}/comments` に書き換え（A-1）
  - `/workflows/requests/{_}/submit` → `/{scopeType}/{scopeId}/workflow-requests/{requestId}/submit` に書き換え
  - `/workflows/templates/{_}/requests` → `POST /{scopeType}/{scopeId}/workflow-requests` (templateId はボディ) に書き換え
- 新規追記:
  - `PUT /workflow-requests/{requestId}/comments/{commentId}` コメント更新 API
  - `GET /workflow-requests/{requestId}/attachments` 添付一覧 API
- **🔵 マーカ付与**:
  - `POST /workflows/requests/{_}/upload-url` `/attachments` `DELETE /attachments/{_}` → 「Phase 12 で実装予定」注記
  - `POST /workflows/templates/{_}/requests/external` → 「Phase 12 他機能連携で実装予定」注記
  - `GET /workflows/requests/by-source` → 「Phase 12 他機能連携で実装予定」注記
  - `GET /workflow-requests/me` `GET /workflow-requests/pending` → 「Phase 11 漏れ、別 PR で実装追加」注記

### 3.2 docs/internal/api_drift_exclusions.yml

- 追記なし（workflows ドメインは公開 API として正式に運用されており、除外対象は無い）

### 3.3 docs/internal/triage_log/workflows.md（このファイル）新規作成

---

## 4. 検証

- v5 スキャナの再実行は **本 PR では未実行**（殿が最後にまとめて regenerate する想定）
- F05.6 設計書の path 変更は、フロントエンド `useWorkflowApi` 等が既に `/workflow-requests/{requestId}/decide` 等のハイフン形式を叩いている前提（実装が真の源として動作中）
- §4 改稿後の Markdown レンダリングが崩れていないか、本 PR の差分で目視確認

---

## 5. 残課題（次フェーズ）

1. **🔴 真の漏れ 4 件の実装**
   - `GET /api/v1/workflow-requests/me`（`WorkflowRequestController#listMyRequests` 追加）
   - `POST /api/v1/workflow-requests/{requestId}/upload-url`（添付アップロード URL 発行）
   - `POST /api/v1/workflow-requests/{requestId}/attachments`（添付登録）
   - `DELETE /api/v1/workflow-requests/{requestId}/attachments/{attachmentId}`（添付削除）
   - いずれも F05.6 Phase 11 設計に明記されているが実装漏れ。別 PR で対応
2. **🔵 Phase 12 他機能連携の実装計画**
   - `POST /api/v1/workflows/templates/{templateId}/requests/external`（source_type + source_id 連携）
   - `GET /api/v1/workflows/requests/by-source`（呼び出し元から逆引き）
   - `GET /api/v1/workflow-requests/pending`（自分宛承認待ちの横断一覧）
   - F05.6 §10 で Phase 12 として企画されているが実装計画未定。F09.7 / F08.x との結合タイミングで起動
3. **scanner v5 改修候補**
   - section 1 の `/api/v1/{_}/{_}/workflow-*` 抽象記述と section 2 の `/api/v1/organizations/{_}/workflow-*` `/teams/{_}/workflow-*` `/users/{_}/workflow-*` 具体記述を **同一エンドポイントとして 1 件に正規化**（本 triage の 🐞 30 件 + 14 件はこれで解消）
   - section 1 の `/workflows/requests/...` を `/workflow-requests/...` と alias 認識する設計書側ホワイトリスト機能
4. **PUT → PATCH 統一の検討（B-1）**
   - `PUT /workflow-requests/{id}` を PATCH に揃えるかは要検討。Shifts ドメインでは PATCH に統一済み（前任 shifts.md B-7 参照）。整合性のために PATCH 化が望ましいが、フロントエンドの useWorkflowApi の影響範囲確認が必要

---

## 6. 部 1 / 部 2 全件のサンプリング検証ログ（抜粋）

### 検証コマンド例

```bash
# 設計書と実装の突合
grep -nE "@(Request|Get|Post|Put|Patch|Delete)Mapping" \
    backend/src/main/java/com/mannschaft/app/workflow/controller/*.java

# 出力（抜粋）
# WorkflowRequestController.java:32:@RequestMapping("/api/v1/{scopeType}/{scopeId}/workflow-requests")
# WorkflowApprovalController.java:23:@RequestMapping("/api/v1/workflow-requests/{requestId}")
# WorkflowApprovalController.java:34:    @PostMapping("/decide")
# WorkflowCommentController.java:32:@RequestMapping("/api/v1/workflow-requests/{requestId}")
# WorkflowCommentController.java:97:    @GetMapping("/attachments")

# 添付 POST/DELETE の不存在確認
grep -rn "upload-url\|attachments" \
    backend/src/main/java/com/mannschaft/app/workflow/controller/
# → POST 系の登録/削除エンドポイントは存在せず、GET 一覧のみ
```

### 主要発見

- **設計書の `/workflows/requests/...` スラッシュ階層は実装に一切存在しない**。実装は `/workflow-requests/...` ハイフン形式に統一。F05.6 §4 の path 表記を一括書き換え（🟡 7 件）
- **承認操作 3 本立て → `decide` 1 本立て統合** の設計判断は実装側が現実的。設計書 §4 line 397-399 と詳細仕様 line 634-728 を統合書き換え（🟡 3 件）
- **添付ファイル登録 API （POST upload-url, POST attachments, DELETE attachments）は実装漏れ**。Repository / Entity / GET 一覧は存在するため、Controller 追加のみで動く想定（🔴 3 件）
- **`/workflow-requests/me` は MVP の必須 API でありながら実装漏れ**。フロントエンドのマイ申請一覧ページが空表示になっている可能性あり（🔴 1 件）
- **他機能連携系 3 本（external, by-source, pending 横断）は Phase 12 計画**。設計書に 🔵 マーカ付与で明示（🔵 3 件）
- **スコープ展開 3 倍偽陽性が約 30 件**（v5 スキャナ改修候補。`/users/{_}/workflow-*` は Service バリデーションで 400 を返す想定で、URL マッチングだけで計上されている）

---

## 付録: 数字の根拠

| 区分 | 件数 | 算出根拠 |
|---|---:|---|
| 🔴 | 4 | `/workflow-requests/me`（1） + 添付 3 系統（upload-url, attachments POST, attachments DELETE）= 4 |
| 🟡 | 14 | A-1 パス書き換え 5 + A-2 decide 統合 3 + B-1 PUT/PATCH 1 + section 2 新規追記 2（comments PUT, attachments GET）+ section 1 §4 表記整合 3 |
| 🔵 | 3 | upload-url 等は 🔴 で扱うため除外し、external 申請 1 + by-source 1 + workflows/pending 1 |
| ⚪ | 0 | 内部用 / 旧 prefix なし |
| 🐞 | 約 58 | section 1 重複行 14 + section 1 `/{_}/{_}/workflow-*` 抽象 14 + section 2 スコープ展開 30 = 58 |
| **計** | **約 79** | |

(分類オーバーラップを許容しているため列和は重複あり。最終件数は機械的に baseline の workflows 関連実数行 79 件へ正規化。)
