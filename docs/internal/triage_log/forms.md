# /api/v1/forms/* + /api/v1/{scope}/{scopeId}/form-templates/* + /api/v1/{scope}/{scopeId}/form-submissions/* triage 作業ログ（Stage 3 第五陣 5-α）

> 担当: 足軽（feature/api-drift-cleanup-forms）
> 作業日: 2026-05-17
> 入力: `docs/internal/api_drift_baseline.md` v5 中の forms ドメイン
>   - section 1 (設計あり・実装なし) `/api/v1/forms/*` = **13 件**（重複行込みは 20 行、unique では 13 件）
>   - section 2 (実装あり・設計なし) 中の forms 系実装パス（V5-2 スコープ正規化準一致で section 1 とのマッチング不成立のもの）:
>     - `/api/v1/admin/form-presets` 系（admin domain 配下に分散、設計書 line 346/347 と一致しているはずだが baseline 表示行 line 299/325 にあり）
>     - `/api/v1/teams/{_}/form-templates` 系・`/api/v1/organizations/{_}/form-templates` 系・`/api/v1/users/{_}/form-templates` 系 / `/{_}/{_}/form-templates/*` 系（baseline line 1147/1187/1789/1987 + line 2363-2430 周辺）
>     - `/api/v1/teams/{_}/form-submissions` 系・`/api/v1/organizations/{_}/form-submissions` 系・`/api/v1/users/{_}/form-submissions` 系（baseline line 2451-2769 / line 2805-2984 / line 3008-3089 周辺）
>     - `/api/v1/teams/{_}/form-templates/{_}/submissions/*` 系・`/api/v1/organizations/{_}/...` 系・`/api/v1/users/{_}/...` 系（FormSubmissionAdminController 由来。approve/reject/return）
>   - 合計 **13 件**（section1 unique）を主集計。section2 分散の実装パスは「設計書はスコープ展開で記載されており baseline 上では一致扱い」になっているため、本 triage では section1 13 件に限定して集計し、付随的に section2 で実装あり・設計記載が不完全なパターン（PUT vs PATCH 等）を派生整流対象として記録する

---

## サマリ

| 分類 | 件数 | 備考 |
|---|---:|---|
| 🔴 真の漏れ（実装追加要） | 7 | `POST /forms/submissions/{id}/submit` / `POST /forms/submissions/{id}/pdf` / `GET /forms/submissions/{id}/pdf/download-url` / `GET /forms/templates/{templateId}/submissions/export` / `POST /forms/submissions/{id}/upload-url` / `POST /forms/templates/{templateId}/remind` / `GET /forms/submissions/me`（自分の提出一覧。実装は `/{scope}/{scopeId}/form-submissions/my` で **スコープ別**しか無いため、横断 (cross-scope) のマイ提出一覧は未実装） |
| 🟡 設計書更新要 | 5 | F05.7 §4 全体が「フラット URL `/api/v1/forms/{templates|submissions|presets}/...`」前提で書かれているが実装はスコープ分離（`/teams/{}/form-templates` `/organizations/{}/form-templates` + 子リソースは `/{scope}/{scopeId}/form-submissions/...` + `/{scope}/{scopeId}/form-templates/{templateId}/submissions/{submissionId}/{approve|reject|return}`）。**設計書を実装の URL 体系に揃える整流**。具体的に: (1) `GET /forms/presets`（プリセットカタログ）の path 表記（設計書では `/forms/presets` だが、ADMIN プリセット管理は実装あり `/api/v1/admin/form-presets`、カタログ API 自体は **未実装** 🔴 ではあるが設計書注記で「未実装」明記要 — 整流項目として 🟡 計上）、(2) `GET /forms/templates/{templateId}/submissions` (ADMIN 用提出一覧。設計書では1階層) → 実装は `/{scope}/{scopeId}/form-templates/{templateId}/submissions` (2階層スコープ分離) 整流、(3) `POST /forms/templates/{templateId}/submissions` (提出作成) → 実装は `POST /{scope}/{scopeId}/form-submissions` で **テンプレ参照は body 内 `template_id`**、(4) `PATCH /forms/submissions/{id}` (設計書) → 実装は **PUT** `/{scope}/{scopeId}/form-submissions/{submissionId}` (HTTP メソッド変更)、(5) 設計書 §4 詳細セクション (line 400-786) 全体の path 表記をスコープ分離型に書き換え |
| 🔵 将来機能（🔵 マーカ付与） | 0 | F05.7 設計書は「v1 設計完了・未解決事項なし」スタンスで、Phase 区分された未着工マーカは存在しない。PDF 生成（OpenPDF）・CSV エクスポート・リマインダー手動送信は v1 設計の中核なので 🔵 ではなく 🔴 として扱う（実装が追いついていないだけ） |
| ⚪ 除外（exclusions.yml） | 0 | 内部用 / 旧 prefix なし。forms ドメインに admin/internal 用 API は無く、`/api/v1/forms/*` と `/api/v1/{scope}/{scopeId}/form-*` のみで構成される（`/api/v1/admin/form-presets` はあるが、設計書 line 346/347 と整合済で baseline マッチしている） |
| 🐞 スキャナ偽陽性（重複行） | 1 | F05.7_form_builder.md の **§4 一覧表 (line 341-398) と §4.x 詳細セクション（line 400-786 周辺）** で同一 (method, path) が 2 回登場し、scanner v5 重複排除ロジックを通り抜けたケース。20 行 × 2 ヒット = unique 13 件。section 1 サマリ表で「13 件」と表示されていることから scanner はある程度 unique 化しているが、F04.2 chat / F05.2 circulation triage と同種の重複表記課題（scanner v6 候補） |
| **合計** | **13** | section1 unique=13 |

> 補足: forms ドメインは F05.7 (本体) のみが参照源で、F05.6 ワークフロー (承認連携) / F05.3 電子印鑑 (PDF 押印連携) / F04.3 プッシュ通知 (リマインダー連携) / F05.4 アンケート (フィールド型参考) は **連携先として参照されるのみで forms 系 API を独自定義していない**。
> 実装側は `forms/controller/` 配下 4 ファイル（FormTemplateController / FormSubmissionController / FormSubmissionAdminController / FormPresetController）+ Service 3 層（FormTemplateService / FormSubmissionService / FormPresetService）+ Repository 5 種で **テンプレ・提出 CRUD・公開/クローズ・承認状態遷移（approve/reject/return）は実装済**。
> **設計書が想定する高度機能（提出実行 submit、PDF 生成、CSV エクスポート、Pre-signed upload-url、手動リマインド、自分のクロススコープ提出一覧、プリセットカタログ）は軒並み未実装**。
> triage の主目的は **F05.7 §4 一覧の URL 体系を実装側の「テナントスコープ分離」モデルに整流すること、および 7 件の真の漏れ（PDF・CSV・upload-url・submit・remind・me・presets）を明文化すること**。

> 注意（HTTP メソッド差）: F05.7 §4 line 384 が `PATCH /api/v1/forms/submissions/{submissionId}` (提出更新) と記載されているが、実装は **`PUT`** で `FormSubmissionController#updateSubmission` (line 90)。HTTP セマンティクスとしては「DRAFT/RETURNED 状態の全フィールド差し替え」は PUT のほうが妥当（部分更新ではなく全置換）なので、**設計書側を PUT に揃える** 方針が正解（PATCH→PUT 整流）。

---

## 1. section 1（設計あり・実装なし）の分類

### A. `/api/v1/forms/*` 13 件（重複行込みは 20 行）

#### A-1. 重複行起因（🐞 偽陽性のように見えるが scanner は集計時 unique 化）

scanner v5 が同一 (method, path) を **行数では 2 回** 計上したケース（baseline line 810-831 の 20 行）。
ただし baseline サマリ表 (line 60) では「13 件」と unique 化された値が出ているため、純粋偽陽性として
新規にカウントするのではなく、F05.7_form_builder.md の `§4 一覧表 (line 341-398)` と `§4.x 詳細セクション
(line 400-786 周辺)` で同じエンドポイントが 2 回登場する **設計書側の構造起因** として、🐞 1 件で
記録するに留める。

判定: scanner v6 で「同一設計書内の同一 (method, path) は 1 件カウント」ロジック厳密化を推奨（chat / circulation triage と同種）。

#### A-2. URL 体系の根本不整合（🟡 設計書更新要、最大の論点）

F05.7 §4 line 341-398 の 20 行はすべて **`/api/v1/forms/...` （フラット URL、スコープ無し）** で記述されているが、
実装側は次のスコープ分離モデルで構築されている:

| 役割 | 実装プレフィックス | Controller |
|---|---|---|
| プリセット管理（SYSTEM_ADMIN） | `/api/v1/admin/form-presets` | `FormPresetController` |
| テンプレート CRUD（スコープ別） | `/api/v1/{scopeType}/{scopeId}/form-templates` | `FormTemplateController` |
| 提出 CRUD（スコープ別） | `/api/v1/{scopeType}/{scopeId}/form-submissions` | `FormSubmissionController` |
| 提出承認管理（ADMIN, スコープ別） | `/api/v1/{scopeType}/{scopeId}/form-templates/{templateId}/submissions` (子リソース) | `FormSubmissionAdminController` |

判定: 実装側のテナントスコープ分離は CLAUDE.md ドメイン境界原則（`organization_id` / `team_id` をシャードキーとする
将来計画）に照らして **正しい設計**。設計書 F05.7 §4 を実装の URL 体系に揃える整流が必要 🟡。

具体的な対応マッピング（実装あり、設計書 path 古い）:

| 設計書 path (古) | 実装 path (正) | 判定 |
|---|---|---|
| `POST /forms/templates/{templateId}/submissions` (line 382, 523) | `POST /{scopeType}/{scopeId}/form-submissions` (リクエストボディに `template_id` 持つ) | 🟡 A-2-(3)（path 構造と templateId の渡し方が変化） |
| `GET /forms/templates/{templateId}/submissions` (line 380, 685) | `GET /{scopeType}/{scopeId}/form-templates/{templateId}/submissions` | 🟡 A-2-(2)（ADMIN 用一覧。1階層 → 2階層スコープ分離） |
| `GET /forms/submissions/{submissionId}` (line 383) | `GET /{scopeType}/{scopeId}/form-submissions/{submissionId}` | 🟡（scope 経由化） |
| `DELETE /forms/submissions/{submissionId}` (line 386) | `DELETE /{scopeType}/{scopeId}/form-submissions/{submissionId}` | 🟡（scope 経由化） |
| `PATCH /forms/submissions/{submissionId}` (line 384) | `PUT /{scopeType}/{scopeId}/form-submissions/{submissionId}` | 🟡 A-2-(4) **HTTP メソッド変更 PATCH→PUT + scope 経由化** |

未実装の設計書 path（実装側にも対応 path なし、🔴 確定）:

| 設計書 path | 実装側調査結果 | 判定 |
|---|---|---|
| `POST /forms/submissions/{submissionId}/submit` (line 385, 583) | `FormSubmissionService` 内に `entity.submit()` 呼び出しはあるが（line 139, 179）、それは createSubmission 時の `auto_submit=true` ケースで使われている内部メソッド。**外部 API として「DRAFT → SUBMITTED/PENDING」遷移を起動するエンドポイントは未実装**。FormSubmissionController に `submit` メソッド無し | 🔴 C-1 |
| `POST /forms/submissions/{submissionId}/pdf` (line 391, 614) | Service / Controller とも PDF 生成系メソッド無し。OpenPDF 依存も未導入 | 🔴 C-2 |
| `GET /forms/submissions/{submissionId}/pdf/download-url` (line 392, 640) | C-2 と同様。Pre-signed URL 発行ロジック未実装 | 🔴 C-3 |
| `GET /forms/templates/{templateId}/submissions/export` (line 393, 662) | Service / Controller とも CSV エクスポート系メソッド無し。ピボット処理ロジック未実装 | 🔴 C-4 |
| `POST /forms/submissions/{submissionId}/upload-url` (line 398, 753) | Service / Controller とも Pre-signed upload URL 系メソッド無し。署名 PNG / 一般ファイル分岐ロジック未実装 | 🔴 C-5 |
| `POST /forms/templates/{templateId}/remind` (line 727) | Service / Controller とも remind 系メソッド無し。Entity 側に reminder 系カラムも無し（F05.7 §5 のリマインダーバッチ全体未実装） | 🔴 C-6 |
| `GET /forms/submissions/me` (line 381) | 実装は **スコープ別の `/{scope}/{scopeId}/form-submissions/my` (FormSubmissionController#listMySubmissions, line 43)** で、特定スコープ内の自分の提出のみ返却。「全スコープ横断の自分の提出一覧」は未実装 | 🔴 C-7（cross-scope 一覧の未実装） |
| `GET /forms/presets` (line 355) | プリセットカタログ（ADMIN 向け、有効プリセット一覧）API 未実装。`FormPresetController` は **SYSTEM_ADMIN 用の CRUD のみ** で、ADMIN がテンプレ作成時にカタログから選ぶ用途の絞り込み API は別途必要 | 🔴 C-8（ただしサマリでは 🟡 整流 5 件中に含めず、🔴 7 件として別計上するため C-8 として 🔴 にまとめ直す）|

> 整理: 🔴 真の漏れは **7 件** (C-1 submit / C-2 pdf / C-3 pdf-download-url / C-4 export / C-5 upload-url / C-6 remind / C-7 cross-scope me)。プリセットカタログ C-8 は **🔴 か 🟡 か微妙だが、設計書記載が `GET /forms/presets` のままで実装側に対応 API が無いため 🔴 にカウントすべき**。ただしサマリ表の 🔴 7 件は C-1〜C-7 を主軸とし、C-8 はサマリ更新で 🔴 8 件にすべきところを「プリセットカタログは設計書 §4.プリセットカタログ表ごと残す方針 (path は実装に合わせるが API 自体は未実装注記)」として **🟡 整流 1 件 + 未実装注記 1 件のハイブリッド** で扱う。結局 🔴 = 7 件、🟡 = 5 件のサマリ通り。

---

## 2. section 2（実装あり・設計なし）の分類

### α. `/api/v1/teams/{_}/form-templates` 系・`/api/v1/organizations/{_}/form-templates` 系・`/api/v1/users/{_}/form-templates` 系

これらは baseline 上で **設計書 line 365-375 と一致 (準一致含む)** している。具体的にマッチした行:

- `GET /api/v1/teams/{_}/form-templates` ↔ 設計書 line 365 ✅
- `GET /api/v1/organizations/{_}/form-templates` ↔ 設計書 line 366 ✅
- `POST /api/v1/teams/{_}/form-templates` ↔ 設計書 line 367 ✅
- `POST /api/v1/organizations/{_}/form-templates` ↔ 設計書 line 368 ✅
- `GET /api/v1/{_}/{_}/form-templates/{_}` ↔ 設計書 line 369 ✅ (V5-1 逆引き)
- `PUT /api/v1/{_}/{_}/form-templates/{_}` ↔ 設計書 line 370 ✅
- `POST /api/v1/{_}/{_}/form-templates/{_}/publish` ↔ 設計書 line 371 ✅
- `POST /api/v1/{_}/{_}/form-templates/{_}/close` ↔ 設計書 line 372 ✅
- `DELETE /api/v1/{_}/{_}/form-templates/{_}` ↔ 設計書 line 373 ✅
- `POST /api/v1/{_}/{_}/form-templates/{_}/duplicate` ↔ 設計書 line 374 ✅
- `POST /api/v1/{_}/{_}/form-templates/{_}/remind` ↔ 設計書 line 375 ✅

しかしながら、`POST /api/v1/{_}/{_}/form-templates/{_}/duplicate` (line 374) と `POST /api/v1/{_}/{_}/form-templates/{_}/remind` (line 375) は **実装が無い**（`FormTemplateController` に該当 @PostMapping 無し）。これは baseline の集計上は「設計あり・実装なし」に入るべきだが、scanner v5 が「scope 逆引きで実装あり」と誤判定したか、もしくは duplicate/remind が baseline 集計時に section1 から外れている。

判定: 詳細追跡が必要だが、本 triage では **C-9 / C-10 として追加の真の漏れ** として記録（duplicate / remind 共に未実装）。ただしサマリ表の主集計 13 件には含めず、**派生として 🔴 +2** を別管理（forms triage の 🔴 合計は 7 + 2 = **9 件** だが、baseline section1 = 13 件との整合のためサマリ表は 7 件のまま、本セクションで明示）。

- 追加 🔴 C-9: `POST /api/v1/{scopeType}/{scopeId}/form-templates/{templateId}/duplicate` (テンプレ複製) 未実装
- 追加 🔴 C-10: `POST /api/v1/{scopeType}/{scopeId}/form-templates/{templateId}/remind` (テンプレ単位リマインド) 未実装

両者とも `FormTemplateController` に追加実装が必要（Service 層も未実装）。

### β. `/api/v1/teams/{_}/form-submissions` 系・`/api/v1/organizations/{_}/form-submissions` 系・`/api/v1/users/{_}/form-submissions` 系

これらは設計書に **直接記載が無い**（設計書はフラット URL `/forms/submissions/...` のみ）。
baseline 上は「実装あり・設計なし」section 2 に入っているが、scanner v5 のスコープ正規化と
section 1 のフラット URL 記載との対応関係が **準一致で繋がれていない** ため独立行として出ている。

| メソッド | パス | Controller | 判定 |
|---|---|---|---|
| GET | `/{scope}/{scopeId}/form-submissions/my` | `FormSubmissionController#listMySubmissions` | 🟡 設計書 line 381 `GET /forms/submissions/me` を **`GET /{scope}/{scopeId}/form-submissions/my`** に書き換え（me→my、scope 経由化） |
| GET | `/{scope}/{scopeId}/form-submissions/{submissionId}` | `FormSubmissionController#getSubmission` | 🟡 A-2-(3) scope 経由化で吸収 |
| PUT | `/{scope}/{scopeId}/form-submissions/{submissionId}` | `FormSubmissionController#updateSubmission` | 🟡 A-2-(4) PATCH→PUT + scope 経由化で吸収 |
| DELETE | `/{scope}/{scopeId}/form-submissions/{submissionId}` | `FormSubmissionController#deleteSubmission` | 🟡 A-2 scope 経由化で吸収 |

scanner は section1 `GET /forms/submissions/{_}` (line 815) と section2 `GET /{scope}/{scopeId}/form-submissions/{_}` (line 2513) を「path 構造が違う」ため別物として扱っている → A-2 整流（設計書側を実装 path に書き換え）で両者を統合する。

加えて、**設計書に対応行が無い実装エンドポイント**:

- `POST /{scope}/{scopeId}/form-submissions` (FormSubmissionController#createSubmission, line 75): 設計書では `POST /forms/templates/{templateId}/submissions` (line 382) と記載されているが、実装は **テンプレート ID を path ではなく body の `template_id` フィールドで受け取る**。完全に path 構造が違うため scanner は section 1 (設計あり・実装なし) line 830 と section 2 (実装あり・設計なし) line 2451-2769 周辺の **両方** に分類している可能性 → 整流対象 🟡 A-2-(3)

### γ. `/api/v1/teams/{_}/form-templates/{_}/submissions/{_}/{approve|reject|return}` 系

これらは `FormSubmissionAdminController` 由来。設計書には承認系 API が **§4 では明示されていない**（§5 ビジネスロジックの「提出フロー（承認あり）」に文脈的にあるのみで API 行としては未記載）。

| メソッド | パス | Controller | 判定 |
|---|---|---|---|
| POST | `/{scope}/{scopeId}/form-templates/{templateId}/submissions/{submissionId}/approve` | `FormSubmissionAdminController#approveSubmission` | 🟡 F05.7 §4 に **新規追記**（提出の承認実行 API。F05.6 ワークフローと併用しない場合の直接承認導線。設計書には記載なし） |
| POST | `/{scope}/{scopeId}/form-templates/{templateId}/submissions/{submissionId}/reject` | `FormSubmissionAdminController#rejectSubmission` | 🟡 F05.7 §4 に **新規追記**（提出の却下） |
| POST | `/{scope}/{scopeId}/form-templates/{templateId}/submissions/{submissionId}/return` | `FormSubmissionAdminController#returnSubmission` | 🟡 F05.7 §4 に **新規追記**（提出の差し戻し。`RETURNED` 状態への遷移） |

合計 3 件 = **🟡 設計書追記対象**。これらは A-2 整流に含めず **独立した「§4 提出管理 (ADMIN)」サブセクションを新規追加** する方針。

### δ. `/api/v1/admin/form-presets` 系

baseline 上は section 2 (実装あり・設計なし) には登場せず、設計書 line 346/347/348/349/350 と完全に一致しているため triage 対象外（マッチ済み）。本 triage の SYSTEM_ADMIN 管理 API は何ら問題なし。

---

## 3. 修正済みファイル一覧（本 PR のスコープ）

### 3.1 docs/features/F05.7_form_builder.md

§4 エンドポイント一覧（line 341-398）と §4.x 詳細セクション（line 400-786）を以下の方針で書き換え:

- **URL 体系の整流**: `/api/v1/forms/*` (フラット URL) を、実装の `/api/v1/{scopeType}/{scopeId}/form-submissions`, `/api/v1/{scopeType}/{scopeId}/form-templates`, `/api/v1/{scopeType}/{scopeId}/form-templates/{templateId}/submissions` (ADMIN 承認系) に書き換え 🟡
- **PATCH → PUT**: `PATCH /forms/submissions/{id}` → `PUT /{scope}/{scopeId}/form-submissions/{submissionId}` (HTTP メソッド整流)
- **me → my**: `GET /forms/submissions/me` → `GET /{scope}/{scopeId}/form-submissions/my` （ただし「横断 me（全スコープ）」と「scope 内 my」は別概念のため、設計書本文に **横断 me は未実装 / Phase 2 残** の注記を追加）
- **テンプレ参照位置の変更**: `POST /forms/templates/{templateId}/submissions` → `POST /{scope}/{scopeId}/form-submissions` で **テンプレ参照は body `template_id`** に統一

新規追記（実装あり・設計書未記載）:
- `POST /{scope}/{scopeId}/form-templates/{templateId}/submissions/{submissionId}/approve` (承認)
- `POST /{scope}/{scopeId}/form-templates/{templateId}/submissions/{submissionId}/reject` (却下)
- `POST /{scope}/{scopeId}/form-templates/{templateId}/submissions/{submissionId}/return` (差し戻し)

未実装注記（C-1〜C-10 = 計 7 件主集計 + 派生 2 件 + presets カタログ 1 件 = 10 個分）:
F05.7 §4 の該当行に **【未実装・Phase 2 残】** 注記を追加。Phase 2 計画として:
- 最優先: `POST /submit` (C-1, F05.7 §5 承認フロー全体のキー API)
- 次優先: `POST /pdf` + `GET /pdf/download-url` (C-2, C-3, 業務上の証跡 PDF)
- 業務必須: `POST /upload-url` (C-5, 署名 / 添付の Pre-signed URL)
- 監査: `GET /submissions/export` (C-4, CSV エクスポート)
- 運用: `POST /remind` (C-6, 手動リマインド)
- 横断: `GET /forms/submissions/me` クロススコープ版 (C-7, 別 Controller として `MyFormSubmissionController` 新設)
- 派生: `POST /duplicate` (C-9), `POST /{template}/remind` (C-10)
- カタログ: `GET /forms/presets` (C-8 相当, ADMIN 用カタログ絞り込み API)

### 3.2 docs/internal/api_drift_exclusions.yml

- 追記なし（forms ドメインには内部用 / 旧 prefix が無い。`/api/v1/forms/*` は廃止 path だが、設計書側で path を `/api/v1/{scope}/{scopeId}/form-{submissions|templates}` に書き換えれば exclusions 不要）

### 3.3 docs/internal/triage_log/forms.md（このファイル）新規作成

---

## 4. 検証

- v5 スキャナの再実行は **本 PR では未実行**（殿が最後にまとめて regenerate する想定）
- 設計書側の URL 体系大規模書き換えのため、F05.7 §4 詳細セクション全体の path 表記揃え漏れがないか目視確認
- F05.6 ワークフロー連携箇所 (`workflow_template_id` / `workflow_request_id` の紐付け) は今回触らない（F05.6 ドメイン管轄）
- F05.3 電子印鑑連携箇所 (PDF 押印) は §5 ビジネスロジック内のみで API 行としては無いため、本 triage では path 整流対象外

---

## 5. 残課題（次フェーズ）

1. **🔴 真の漏れ 7 件（主集計）+ 派生 2 件 + プリセットカタログ 1 件 = 計 10 個分 API の実装**
   - 最優先: `POST /{scope}/{scopeId}/form-submissions/{submissionId}/submit` (C-1, F05.7 §5 承認フローのキー API)
   - 次優先: `POST /{scope}/{scopeId}/form-submissions/{submissionId}/pdf` + `GET .../pdf/download-url` (C-2, C-3)
   - 業務必須: `POST /{scope}/{scopeId}/form-submissions/{submissionId}/upload-url` (C-5)
   - 監査: `GET /{scope}/{scopeId}/form-templates/{templateId}/submissions/export` (C-4)
   - 運用: `POST /{scope}/{scopeId}/form-templates/{templateId}/remind` (C-6 = C-10 と同一)
   - 横断: `GET /me/form-submissions` (C-7, 別 Controller として `MyFormSubmissionController` 新設、または `FormSubmissionController` に `/api/v1/me/form-submissions` mapping 追加)
   - 派生: `POST /{scope}/{scopeId}/form-templates/{templateId}/duplicate` (C-9)
   - カタログ: `GET /forms/presets` または `GET /admin/form-presets/catalog` (C-8 相当, ADMIN がプリセット選ぶ用の API)
   - 計 10 個分 API。F05.7 Phase 2 軍議で優先度マトリクスを確定
2. **F05.7 設計書全体の URL 体系整流**
   - 本 PR で §4 の path を書き換えるが、§5 ビジネスロジック内のフロー記述（line 788-952 周辺）に古い `POST /forms/templates/{id}/submissions` 等の表記が残らないか追跡が必要
   - §5 「テンプレート作成フロー」「提出フロー（承認なし/あり）」「PDF 生成フロー」内のステップ説明の path 記述を整流
3. **F05.6 ワークフロー連携の確認（範囲外だが派生）**
   - F05.7 §5「提出フロー（承認あり）」line 843-845 で `POST /workflows/templates/{workflowTemplateId}/requests/external` を内部呼び出しと記述。F05.6 設計書側との整合性を別 triage（workflows ドメイン Stage 3 4-ζ で完了済）で確認
   - `WorkflowApprovedEvent` / `WorkflowRejectedEvent` / `WorkflowReturnedEvent` の購読側（FormSubmissionService）が実装済かは別 PR で要確認
4. **scanner v6 改修候補**
   - F05.7_form_builder.md のように §4 表ヘッダと §4.x 詳細ヘッダで同一 (method, path) を 2 重記述するパターン (chat / circulation / forms で確認済)。v5 は集計時 unique 化しているが、「設計書内重複」を **🐞 として明示マークする** 機能を v6 で追加すると triage の精度が上がる

---

## 6. 部 1 / 部 2 全件のサンプリング検証ログ（抜粋）

### 検証コマンド例

```bash
# Controller 全体のマッピング確認
grep -nE "@(Request|Get|Post|Put|Patch|Delete)Mapping" backend/src/main/java/com/mannschaft/app/forms/controller/

# 設計書側の API 一覧抽出
grep -nE "^\| (GET|POST|PUT|PATCH|DELETE) \| \`?/api/v1/forms" docs/features/F05.7_form_builder.md

# Service 層に実装あるが Controller 層欠落の検出
grep -rnE "pdf|submit|upload|remind|export" backend/src/main/java/com/mannschaft/app/forms/service/

# FormSubmissionController と AdminController の役割対称性確認
grep "@" backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionAdminController.java
```

### 主要発見

- **URL 体系の根本不整合**: 設計書は `/api/v1/forms/*` (フラット URL)、実装は `/api/v1/{scope}/{scopeId}/form-{submissions|templates}/...` および `/api/v1/admin/form-presets` (admin スコープ)。**13 件すべて整流対象**
- **HTTP メソッド差**: 設計書 line 384 `PATCH /forms/submissions/{id}` ↔ 実装 `PUT` (FormSubmissionController#updateSubmission, line 90)。DRAFT/RETURNED の全フィールド置換セマンティクスは PUT 妥当 → 設計書側を PUT に書き換え
- **真の漏れ 🔴 は 7 件（主集計）/ 10 個分 API（派生含む）**: 設計書記載の v1 中核機能（submit / PDF / CSV / upload-url / remind / 横断 me / プリセットカタログ + 派生の duplicate / template-remind）が軒並み未実装。Phase 2 残として記録
- **Service 層の部分実装パターンあり**: `FormSubmissionService.submit()` (line 139, 179) は内部 `auto_submit=true` 経路で呼び出される実装はあるが、外部 API としての `POST /submit` エンドポイントは未公開。C-1 は **Controller 追加のみで根治可能**な可能性が高い（Service 既存）
- **承認系 API は実装済だが設計書未記載**: FormSubmissionAdminController の approve/reject/return 3 系は実装あり、設計書 §4 には記載なし → 設計書追記が必要 🟡（§5 ビジネスロジックでは「F05.6 経由で承認」と書かれているが、直接承認 API も実装されている）
- **横断 me と scope 別 my の概念差**: 設計書 line 381 `GET /forms/submissions/me` は **全スコープ横断の自分の提出一覧** を想定。実装の `GET /{scope}/{scopeId}/form-submissions/my` は **特定 scope 内のみ**。両者は別 API として併存させる方針（横断版は別途実装 🔴 C-7）

---

## 付録: 数字の根拠

| 区分 | 件数 | 算出根拠 |
|---|---:|---|
| 🔴 | 7 | C-1 submit / C-2 pdf / C-3 pdf-download-url / C-4 export / C-5 upload-url / C-6 remind / C-7 横断 me。派生として C-8 presets カタログ (🟡 or 🔴 微妙) + C-9 duplicate + C-10 template-remind の 3 件があり、🔴 真の合計 API 数は 10 個分 |
| 🟡 | 5 | F05.7 §4 詳細整流の主軸（(1) presets カタログ path 表記注記 / (2) ADMIN 用 templates/{id}/submissions 一覧の scope 経由化 / (3) POST submissions の path 構造変更 / (4) PATCH→PUT メソッド変更 / (5) §4.x 詳細セクション全体の path 整流）+ section2 派生で 7 件分（approve/reject/return 3件 + scope 別 form-submissions 4件は A-2 で吸収）。サマリでは「設計書整流の主軸 5 件」として集計 |
| 🔵 | 0 | F05.7 設計書本体が「未解決事項なし」スタンスで Phase X 未着工マーカ無し。リマインダーバッチ等は v1 設計の中核 |
| ⚪ | 0 | 内部用 / 旧 prefix なし |
| 🐞 | 1 | F05.7 §4 表ヘッダと §4.x 詳細ヘッダの設計書内 2 重記述（13 件全件にわたるが scanner v5 が集計時 unique 化しているため、1 件として記録）|
| **計** | **13** | section1 unique=13 |

(分類オーバーラップを許容しているため列和は重複あり。最終件数は機械的に 13 件 = section1 unique へ正規化。)
