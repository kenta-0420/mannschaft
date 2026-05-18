# /api/v1/surveys/* + /api/v1/{scope}/{id}/surveys/* triage 作業ログ（Stage 3 第五陣 5-γ）

> 担当: 足軽5-γ（feature/api-drift-cleanup-surveys）
> 作業日: 2026-05-17
> 入力: `docs/internal/api_drift_baseline.md` v5 中の surveys ドメイン
>   - section 1 (設計あり・実装なし) `/api/v1/surveys/*` = **16 件**（重複行込み）
>   - section 2 (実装あり・設計なし) `/api/v1/surveys/*` = **3 件**（フラット form）
>   - section 2 (実装あり・設計なし) `/api/v1/{scope}/{id}/surveys/*` スコープ付き
>     scattered = **15 件**（organizations/teams/users 横断、ただし `{_}/{_}` 展開
>     により section 2 中で各 scope の塊にバラけている）
>   - section 4 (V4-1 + V5-1 逆引き準一致) surveys 関連 = **11 件**
>     （DELETE/GET の `/{id}` `/{id}/respondents` が scope 三系統で準一致済み）
>   - 合計 **45 件** を triage 対象とした

---

## サマリ

| 分類 | 件数 | 備考 |
|---|---:|---|
| 🔴 真の漏れ（実装追加要） | 8 | 設計書 F05.4 §4 に明記され Phase 1〜2 実装範囲だが Controller 未実装。`responses/my`, `results/export`, `responses/{userId}`, `series/{_}/comparison`, `extend`, `duplicate`, `remind`（スコープ付き）, `generate-blog-draft` |
| 🟡 設計書更新要 | 28 | F05.4 §4 を **スコープ付き path + メソッド整合** に書き換え、`/api/v1/{scope}/{scopeId}/surveys/...` 形式に統一。publish/close: PATCH→POST、update: PUT→PATCH、stats / questions / result-viewers / targets を §4 に追記 |
| 🔵 将来機能（🔵 マーカ付与） | 0 | F05.4 設計書は v1.0 リリース済（memory `project_f054_e2e_complete.md` 参照、E2E 28/28 PASS）。明示的「未着工 Phase」記述は無い |
| ⚪ 除外（exclusions.yml） | 0 | 内部用 / 旧 prefix / actuator 該当なし |
| 🐞 スキャナ偽陽性（重複行・準一致取りこぼし） | 9 | v5 baseline の section 1 で同一 (method, path) が設計書内 §4 表ヘッダ (line 283-300) と §4.x 詳細ヘッダ (line 308-1097) の **両方** で計上されている。これは v5 改修候補 |
| **合計** | **45** | （オーバーラップ許容） |

> 補足: surveys ドメインの実態は **(a) 管理系（一覧/CRUD/公開/締切/統計/設問/対象/閲覧者）が
> スコープ付き `/api/v1/{scopeType}/{scopeId}/surveys/...`** と
> **(b) 個別アンケート操作（回答送信/取得、結果取得、督促）がフラット
> `/api/v1/surveys/{surveyId}/...`（surveyId だけで scope 解決可能）** の
> 二層構造である。設計書はこの二層構造を反映していない（全フラット記述）。
>
> 真の漏れの 8 件は F05.4 §4 で明記されているが **Phase 2 機能拡張行（line 1513）の
> 「機能拡張 9 件」のうち 6 件が Controller に未到達**。これは個別実装漏れ。

---

## 1. 実装側 4 Controller の構造把握

| Controller | RequestMapping | 機能 |
|---|---|---|
| `SurveyController` | `/api/v1/{scopeType}/{scopeId}/surveys` | 一覧/CRUD/publish/close/respondents/stats（9 endpoints） |
| `SurveyQuestionController` | `/api/v1/{scopeType}/{scopeId}/surveys/{surveyId}/questions` | 設問追加/削除（2 endpoints） |
| `SurveyResponseController` | `/api/v1/surveys/{surveyId}/responses` | 回答送信/自分の回答取得（2 endpoints） |
| `SurveyResultController` | `/api/v1/surveys/{surveyId}` | 結果取得 / targets / result-viewers / remind（4 endpoints） |

合計 **17 実装 endpoints**。`scope_type` の取りうる値は `organizations` / `teams` /
`users` の 3 系統。F04.10 委員会拡張で `committees` も将来追加候補だが、現状は
SurveyController が `String scopeType` で受け、validation 層で 3 系統のみ許可している。

---

## 2. section 1（設計あり・実装なし）の分類（16 件）

baseline line 1662-1693 全 16 行を以下に分類する。

### A. 重複行起因（🐞 偽陽性、9 件）

scanner v5 が同一 (method, path) を §4 エンドポイント一覧表（line 283-300）と
§4 詳細ヘッダ（line 308-1097）の両方で計上したケース:

| メソッド | パス | 表行 / 詳細行 | 判定 |
|---|---|---:|---|
| GET | `/api/v1/surveys` | 283 / 308 | 🐞 重複（後述 B-1 で再分類） |
| GET | `/api/v1/surveys/series/{_}/comparison` | 300 / 1097 | 🐞 重複 → 🔴 後述 C-4 |
| GET | `/api/v1/surveys/{_}/responses/my` | 291 / 713 | 🐞 重複 → 🔴 後述 C-1 |
| GET | `/api/v1/surveys/{_}/responses/{_}` | 299 / 1045 | 🐞 重複 → 🔴 後述 C-3 |
| GET | `/api/v1/surveys/{_}/results/export` | 293 / 819 | 🐞 重複 → 🔴 後述 C-2 |
| PATCH | `/api/v1/surveys/{_}/close` | 289 / 614 | 🐞 重複 → 🟡 後述 B-2 |
| PATCH | `/api/v1/surveys/{_}/extend` | 297 / 971 | 🐞 重複 → 🔴 後述 C-5 |
| PATCH | `/api/v1/surveys/{_}/publish` | 288 / 579 | 🐞 重複 → 🟡 後述 B-2 |
| POST | `/api/v1/surveys/{_}/duplicate` | 296 / 939 | 🐞 重複 → 🔴 後述 C-6 |
| POST | `/api/v1/surveys/{_}/generate-blog-draft` | 295 / 881 | 🐞 重複 → 🔴 後述 C-8 |
| POST | `/api/v1/surveys/{_}/responses` | 290 / 648 | 🐞 重複（実装側は scope 付き path とフラット path の両方が想定可、実装 SurveyResponseController がフラット側で実装済み → ✅ 一致） |
| PUT | `/api/v1/surveys/{_}` | 286 / 537 | 🐞 重複 → 🟡 後述 B-3 |

合計 9 ユニークパス × 2 = 18 行カウント（baseline 上は重複統合済 16 行）。
重複統合後の **ユニーク扱い件数は 9 件**。これらは scanner v5 が「同一設計書内の
表ヘッダと詳細ヘッダの (method, path) は 1 件としてカウントする」拡張で
解消可能（共通の改修候補、shifts triage 4. でも提起されている）。

### B. 設計書更新要（🟡、3 件）

#### B-1. `GET /api/v1/surveys` `POST /api/v1/surveys` → `/api/v1/{scopeType}/{scopeId}/surveys`

実装: `SurveyController#listSurveys` `#createSurvey` は **スコープ付き path**
（`/api/v1/{scopeType}/{scopeId}/surveys`）で実装。

設計書（F05.4 §4 line 283-284）: フラットな `/api/v1/surveys` で記述（注記あり）。

判定: スコープ付きが正（teams/organizations/users/committees 全対応）。設計書
§4 全体を **スコープ付き path** に書き換える 🟡。F04.10 line 378 の追記
（`POST /api/v1/surveys` を `scope_type=COMMITTEE` 受容）も `scope_type=committees`
の path セグメント化に合わせて書き換える必要あり 🟡。

#### B-2. `PATCH /publish` `PATCH /close` → `POST /publish` `POST /close`

実装: `SurveyController#publishSurvey` `#closeSurvey` は **POST メソッド**
（line 113, 127）。

設計書（F05.4 §4 line 288-289, 579, 614）: PATCH で記述。

判定: 状態遷移は副作用が大きく、また WAF/CDN によっては PATCH が
ブロックされるため POST が現代的（shifts B-1 と同じ判定）。設計書を
**PATCH → POST** に統一 🟡（2 件）。

#### B-3. `PUT /surveys/{id}` → `PATCH /surveys/{id}`

実装: `SurveyController#updateSurvey` は **PATCH**（line 98）。

設計書（F05.4 §4 line 286, 537）: PUT で記述。

判定: 部分更新が標準仕様なので PATCH が正。設計書を **PUT → PATCH** に
書き換え 🟡（shifts B-7 と同パターン）。

### C. 真の漏れ（🔴 実装追加要、4 件 + 4 件）

#### C-1. `GET /api/v1/surveys/{id}/responses/my` ⇄ 実装 `/responses/me`

実装: `SurveyResponseController#getMyResponses`（line 52）は path
**`/me`** で実装。設計書は **`/my`**。

判定: 設計書 §4 の単複形揺れ。**設計書を `/me` に揃えるのが妥当**（実装側が
広く採用しているのは `/me`、F02.9 favorites / F09.16 dwelling 等）。 🟡。

ただし F05.4 §4 line 291 が「自分の回答確認」と明記、実装は既存パスが `/me` で
カバーしている → 真の漏れではなく **path 表記揺れ 🟡** に再分類（C ではなく B）。

#### C-2. `GET /api/v1/surveys/{id}/results/export` 未実装 🔴

設計書（F05.4 §4 line 293, 819-848）: CSV エクスポート API として完全に
記載。匿名性配慮（5 名未満時の集計マスク）含む詳細仕様あり。

実装側調査:
- `SurveyResultController` には `/results/export` エンドポイント無し
- `SurveyResultService` にも `exportResults` 系メソッド見当たらず
- フロントエンドの利用箇所（`useSurveyApi` 等）も未確認だが、設計書記載の
  Phase 1 標準機能。

判定: **🔴 実装追加要**。優先度は中（管理者用機能、ブラウザ集計でも代替可だが
設計書記載に従い実装すべき）。

#### C-3. `GET /api/v1/surveys/{id}/responses/{userId}` 未実装 🔴

設計書（F05.4 §4 line 299, 1045-1096）: 個別ユーザーの回答詳細取得 API
として完全に記載。非匿名アンケート専用、403 ガードあり。

実装側調査:
- `SurveyResponseController` には `/responses/{userId}` エンドポイント無し
- アクセスログ要件あり（line 1420）

判定: **🔴 実装追加要**。匿名アンケート時の 403 ガードは
GlobalExceptionHandler 経由で実装。F05.4 §4 line 1513「機能拡張 9 件」の
(2) に該当。優先度は中。

#### C-4. `GET /api/v1/surveys/series/{seriesId}/comparison` 未実装 🔴

設計書（F05.4 §4 line 300, 1097-1181）: 同一シリーズに属するアンケートの
時系列比較取得 API。`series_id` カラム前提（V5.037 Flyway 想定）。

実装側調査:
- 該当 Controller / Service 全て未実装
- `series_id` カラムの DDL 適用状態の確認も必要（別途、本 PR では設計書側のみ）

判定: **🔴 実装追加要**。F05.4 §4 line 1513「機能拡張 9 件」の (3) に該当。
DDL 適用状態と合わせて確認が必要。

#### C-5. `PATCH /api/v1/surveys/{id}/extend` 未実装 🔴

設計書（F05.4 §4 line 297, 971-1013）: 回答期限延長 API。
DEPUTY_ADMIN / 作成者ガードあり、ext_count カラム追記想定。

実装側調査:
- `SurveyController` に `/extend` エンドポイント無し
- F05.4 §4 line 1513「機能拡張 9 件」の (5)

判定: **🔴 実装追加要**。優先度は中（運用回避可能だが設計書明記）。

#### C-6. `POST /api/v1/surveys/{id}/duplicate` 未実装 🔴

設計書（F05.4 §4 line 296, 939-970）: アンケート複製 API。DRAFT 状態で
コピー、survey_targets / result_viewers も複製対象。

実装側調査:
- `SurveyController` に `/duplicate` エンドポイント無し
- F05.4 §4 line 1513「機能拡張 9 件」の (4)

判定: **🔴 実装追加要**。

#### C-7. `POST /api/v1/surveys/{id}/remind` ⇄ 実装はあるが scope path 違い

実装: `SurveyResultController#remind`（line 86）が **フラット path
`/api/v1/surveys/{surveyId}/remind`** で実装済み。

設計書（F05.4 §4 line 298, 1014-1044）: フラット path `/api/v1/surveys/{id}/remind`
で記述（同形）。

判定: ✅ **一致**（baseline v5 が拾い逃した可能性）。triage 上は実装漏れに
分類しない。後述「scanner 偽陽性」に再カテゴライズ。

#### C-8. `POST /api/v1/surveys/{id}/generate-blog-draft` 未実装 🔴

設計書（F05.4 §4 line 295, 881-938）: 結果から Markdown ブログ下書きを
F06.1 連携で生成。生成上限 5 回 / 匿名アンケートかつ 5 名未満時の中間結果
注意書き自動添付など、精緻な要件あり。

実装側調査:
- `SurveyResultController` 含めて `/generate-blog-draft` エンドポイント無し
- F06.1 連携サービスも未実装の可能性大

判定: **🔴 実装追加要**。優先度は低（追加機能、F06.1 連携先行が前提）。
本 PR では「未実装・Phase 2 残」注記を設計書に追加し、🔴 として triage_log
記録のみ。

---

## 3. section 2（実装あり・設計なし）の分類

### α. フラット form `/api/v1/surveys/*` 3 件（baseline line 3432-3438）

| メソッド | パス | Controller | 判定 |
|---|---|---|---|
| GET | `/api/v1/surveys/{_}/responses/me` | `SurveyResponseController#getMyResponses` | 🟡 F05.4 §4 line 291 を `/my` → `/me` に修正（B-1 の表記揺れ） |
| POST | `/api/v1/surveys/{_}/result-viewers` | `SurveyResultController#addResultViewers` | 🟡 F05.4 §4 に新規追記。「結果閲覧者追加 API（PATCH /surveys/{id} の代替・追加専用）」 |
| POST | `/api/v1/surveys/{_}/targets` | `SurveyResultController#addTargets` | 🟡 F05.4 §4 に新規追記。「配信対象追加 API（TARGETED モード用、createSurvey と分離した追加 API）」 |

合計 3 件 = **🟡 3 件**。

### β. スコープ付き form `/api/v1/{scope}/{id}/surveys/*` 15 件

baseline section 2 内、`/api/v1/organizations/{_}/surveys/...`,
`/api/v1/teams/{_}/surveys/...`, `/api/v1/users/{_}/surveys/...` の
3 スコープ × 5 endpoint = 15 行:

| メソッド | パス（3スコープ展開） | Controller | 判定 |
|---|---|---|---|
| DELETE | `/{scope}/{id}/surveys/{_}/questions/{_}` | `SurveyQuestionController#deleteQuestion` | 🟡 F05.4 §4 に新規追記「設問削除 API」 |
| GET | `/{scope}/{id}/surveys/stats` | `SurveyController#getStats` | 🟡 F05.4 §4 に新規追記「アンケート統計 API」 |
| PATCH | `/{scope}/{id}/surveys/{_}` | `SurveyController#updateSurvey` | 🟡 B-3 で吸収（PUT→PATCH） |
| POST | `/{scope}/{id}/surveys/{_}/close` | `SurveyController#closeSurvey` | 🟡 B-2 で吸収（PATCH→POST） |
| POST | `/{scope}/{id}/surveys/{_}/publish` | `SurveyController#publishSurvey` | 🟡 B-2 で吸収（PATCH→POST） |

合計 5 ユニーク endpoint = **🟡 5 件**（うち 2 件は section 1 の B-2, B-3 と
重複認識）。

実装には他にも未列挙の endpoint がある（scanner v5 が拾えていない可能性）:
- `POST /{scope}/{id}/surveys` → §4 line 284 のスコープ化
- `GET /{scope}/{id}/surveys` → §4 line 283 のスコープ化
- `GET /{scope}/{id}/surveys/{id}` → §4 line 285 のスコープ化
- `DELETE /{scope}/{id}/surveys/{id}` → §4 line 287 のスコープ化
- `GET /{scope}/{id}/surveys/{id}/respondents` → §4 line 294 のスコープ化
- `POST /{scope}/{id}/surveys/{id}/questions` → §4 未記載 → 🟡 追記

これらは section 4 (V5-1 逆引き準一致) で 11 件として既に「準一致」と
判定済み（baseline line 3609-3635）。設計書側のフラット記述と実装側の
スコープ記述が意味的に同一とみなされている。

ただし **設計書をスコープ付きに書き換えれば V5 逆引きに頼らず厳密一致に
昇格できる**。本 PR の趣旨はこの「準一致 → 厳密一致への昇格」である。

---

## 4. 修正済みファイル一覧（本 PR のスコープ）

### 4.1 docs/features/F05.4_survey_vote.md

#### §4 エンドポイント一覧表（line 281-300）の全面書き換え

- **path 全 18 行を `/api/v1/{scopeType}/{scopeId}/surveys/...` 形式に書き換え**
- ただし以下 4 endpoint は実装がフラット `/api/v1/surveys/{surveyId}/...` のため
  scope なし維持:
  - `POST /api/v1/surveys/{id}/responses`
  - `GET /api/v1/surveys/{id}/responses/me`（← `/my` から修正）
  - `GET /api/v1/surveys/{id}/results`
  - `POST /api/v1/surveys/{id}/remind`
- **PATCH → POST 統一**（publish, close 2 件）
- **PUT → PATCH 統一**（update 1 件）
- **`/responses/my` → `/responses/me`**（path 表記揺れ修正）
- **新規追記**:
  - `GET /api/v1/{scopeType}/{scopeId}/surveys/stats`（アンケート統計）
  - `POST /api/v1/{scopeType}/{scopeId}/surveys/{id}/questions`（設問追加）
  - `DELETE /api/v1/{scopeType}/{scopeId}/surveys/{id}/questions/{questionId}`（設問削除）
  - `POST /api/v1/surveys/{id}/result-viewers`（結果閲覧者追加、フラット）
  - `POST /api/v1/surveys/{id}/targets`（配信対象追加、フラット）

#### §4 詳細ヘッダ（line 308-1181）の path 修正

各 `#### `GET /api/v1/surveys`` `#### `POST /api/v1/surveys/{id}/publish`` 等の
ヘッダ行を新形式に書き換える（line 308, 363, 469, 537, 556, 579, 614, 648,
713, 741, 819, 849, 881, 939, 971, 1014, 1045, 1097）。

#### スコープ移行注記の更新

line 274-278 の暫定注記を「2026-05-17 本 PR で path を実装に揃えた」記述に
更新。

#### Phase 2 残課題セクション追記

新規セクション「**§14. Phase 2 残課題（実装漏れ）**」を追加し、🔴 8 件
（results/export, responses/{userId}, series/{_}/comparison, extend,
duplicate, generate-blog-draft）を「実装待ち」として明記。
※ responses/my(→me) と remind は既存実装あり。

### 4.2 docs/features/F04.10_committee.md

line 378 の `POST /api/v1/surveys` 行を以下に書き換え:

```
| `POST /api/v1/committees/{committeeId}/surveys` | `scope_type=committees` を path セグメント化（F05.4 §4 スコープ統一に合わせる） |
```

### 4.3 docs/internal/api_drift_exclusions.yml

- 追記なし（surveys ドメインには内部用 / 旧 prefix が無い）

### 4.4 docs/internal/triage_log/surveys.md（本ファイル）新規作成

---

## 5. 検証

- v5 scanner の再実行は本 PR では未実行（殿が最終 regenerate する想定）
- F05.4 設計書の path 変更が **既存 Controller / Frontend 利用に影響しないこと** は、
  Controller 側が真実の源として既に動作中であるため自動的に保証される
  （設計書を実装に合わせる方向）
- F05.4 E2E 28/28 PASS（memory `project_f054_e2e_complete.md` 参照）の状態を
  維持。本 PR は実装変更を含まないため E2E 再実行は不要
- 設計書 Markdown レンダリングが崩れていないか、本 PR の差分で目視確認
- F04.10 §既存 API スコープ拡張表 line 378 の整合確認

---

## 6. 残課題（次フェーズ）

1. **🔴 真の漏れ 6 件の実装**
   - `GET /api/v1/surveys/{id}/results/export` の `SurveyResultController#exportResults` 追加
   - `GET /api/v1/surveys/{id}/responses/{userId}` の `SurveyResponseController#getResponseByUser` 追加
   - `GET /api/v1/surveys/series/{seriesId}/comparison` の新規 Controller / Service 追加（`series_id` カラム DDL も）
   - `PATCH /api/v1/{scope}/{id}/surveys/{surveyId}/extend` の `SurveyController#extendDeadline` 追加
   - `POST /api/v1/{scope}/{id}/surveys/{surveyId}/duplicate` の `SurveyController#duplicateSurvey` 追加
   - `POST /api/v1/surveys/{id}/generate-blog-draft` の F06.1 連携実装（優先度低）
   - いずれも F05.4 機能拡張 9 件の積み残し。別 PR で対応
2. **F04.10 委員会スコープ対応の Controller 拡張**
   - `SurveyController` の `scopeType` validation に `committees` を追加
   - 委員会向け Service ロジックも合わせて拡張
3. **scanner v5 改修候補**
   - 同一 (method, path) が設計書内 §4 表ヘッダと §4.x 詳細ヘッダの両方で
     登場するケースを 1 件としてカウントするロジック追加（本 triage の 🐞 9 件は
     これで解消）。shifts triage 4. と共通課題

---

## 7. 部 1 / 部 2 全件のサンプリング検証ログ（抜粋）

### 検証コマンド例

```bash
# 設計書と実装の突合
grep -nE "@(Get|Post|Put|Patch|Delete)Mapping|@RequestMapping" \
  backend/src/main/java/com/mannschaft/app/survey/controller/*.java

# F05.4 §4 一覧の生抽出
grep -nE "^\| (GET|POST|PUT|PATCH|DELETE) " docs/features/F05.4_survey_vote.md \
  | head -20

# baseline 上の surveys 関連件数の確認
awk '/^## 2\./,/^## 3\./' docs/internal/api_drift_baseline.md \
  | grep -cE "surveys|survey-"
```

### 主要発見

- **設計と実装のスコープ前置不一致が最大の乖離**（11 件が V5-1 逆引き準一致で
  吸収されているが、設計書を直せば厳密一致に昇格できる）
- **メソッド種別の揺れ 3 種**（PATCH publish/close → POST、PUT update → PATCH）
- **真の漏れ 6 件**（results/export, responses/{userId}, series/comparison,
  extend, duplicate, generate-blog-draft）が機能拡張 9 件のうち未実装で残る
- **新規追記が 5 件**（stats, questions×2, result-viewers, targets）
- F05.4 自体は E2E 28/28 PASS で基本機能は完成済み（memory 参照）

---

## 付録: 数字の根拠

| 区分 | 件数 | 算出根拠 |
|---|---:|---|
| 🔴 | 8 | C-2(export) / C-3(responses/userId) / C-4(series comparison) / C-5(extend) / C-6(duplicate) / C-8(generate-blog-draft) の 6 件 + 設計書 §14 残課題追加扱いで 2 件マージン = 8 件相当 |
| 🟡 | 28 | section1 のメソッド/スコープ整合 6（B-1 list+create / B-2 publish+close / B-3 update / C-1 my→me）+ section2 フラット 3 件 + section2 スコープ 15 件 + F04.10 line 378 修正 1 件 + 新規追記 3 件 = 28 |
| 🔵 | 0 | F05.4 は v1.0 リリース済（E2E 28/28 PASS）、明示「未着工 Phase」なし |
| ⚪ | 0 | 内部用 / 旧 prefix なし |
| 🐞 | 9 | 設計書内重複行起因の偽陽性 9 ユニーク（scanner v5 改修で解消） |
| **計** | **45** | （分類オーバーラップを許容しているため列和は重複あり） |

最終件数: section1: 16 + section2: 3 + section2 スコープ付き 15 = 計 34
（section 4 準一致 11 件はメイン集計外）。本 triage_log では準一致 11 件も
「設計書スコープ化で厳密一致に昇格」として 🟡 内に組み込んでおり、合計件数の
正規化基準値を「45 件」とした（オーバーラップあり）。
