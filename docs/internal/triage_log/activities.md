# /api/v1/activities/* + /api/v1/activity-records/* + /api/v1/activity-templates/* + /api/v1/action-memo* triage 作業ログ（Stage 3 第四陣 4-δ）

> 担当: 足軽4-δ（feature/api-drift-cleanup-activities）
> 作業日: 2026-05-17
> 入力: `docs/internal/api_drift_baseline.md` v5
>   - 部 1（設計あり・実装なし）
>     - `### /api/v1/activities/* (20 件)` — path 20 件、表行 37 行（設計書内の重複登場を含む）
>     - `### /api/v1/activity-records/* (1 件)` — path 1 件（F04.10 由来）
>     - `### /api/v1/activity-templates/* (2 件)` — path 2 件（F06.4 由来。`POST` が複数行重複登場）
>     - `### /api/v1/action-memo-settings/* (2 件)` — path 2 件（GET / PATCH、F02.5 由来）
>     - `### /api/v1/action-memo-tags/* (2 件)` — path 2 件（GET / POST）
>     - `### /api/v1/action-memos/* (2 件)` — path 2 件（GET / POST、F02.5 由来）
>   - 部 2（実装あり・設計なし）
>     - `#### /api/v1/action-memos/* (4 件)` — `complete-todo` / `available-orgs` / `mood-stats` / `audit-logs`
>   - サマリ表（サマリ集計値）
>     - `/api/v1/activities/* | 20 | 0 | 11 | 20`（一致 11 ／ 設計のみ 20）
>     - `/api/v1/action-memos/* | 2 | 4 | 10 | 6`
>     - `/api/v1/action-memo-settings/* | 2 | 0 | 0 | 2`
>     - `/api/v1/action-memo-tags/* | 2 | 0 | 2 | 2`
>
> 注: 本ファイルでは便宜上「activities ドメイン」と総称する形で、F06.1 / F06.4 / F04.10 / F02.5 系を一括 triage する。action-memo は本来 F02.5 が責任を持つ別ドメインだが、足軽4-δ 第四陣の任務範囲に含まれているため同一ファイルで処理する（後段で「2. action-memo 系」セクションに集約）。

---

## サマリ

| 分類 | 件数（path 数） | 備考 |
|---|---:|---|
| 🔴 真の漏れ（実装追加要） | 0 | 該当なし。activities/action-memo とも実装は揃っており、乖離は設計書の表記揺れに集約 |
| 🟡 設計書更新要 | 19 | F06.1 §4 活動記録セクションの旧設計群（17 path）+ F04.10 §「他機能との連携」表 1 path + 部 2 の ActionMemo 拡張 4 path のうち F02.5 への追記必要 4 path − 重複統合 = 実体 19 |
| 🔵 将来機能（🔵 マーカ付与） | 0 | activities/action-memo 系で「未着工 Phase」相当のものは無い。F06.1 の活動記録旧設計群は「Phase 6 で既に F06.4 へ機能分離済み」のため 🔵 ではなく 🟡 |
| ⚪ 除外（exclusions.yml） | 0 | 該当なし |
| 🐞 スキャナ偽陽性（重複行起因） | 0 | path 単位の集計は正しい。表内行重複は v5 仕様で許容 |
| **合計 (path)** | **29** | activities 20 + activity-records 1 + activity-templates 2 + action-memo-settings 2 + action-memo-tags 2 + action-memos 2 (= 設計のみ 29) + action-memos 部 2 4 (= 実装のみ 4) = 33 path のうち実装一致重複 4 を引いて 29 |

> 補足: **「真の漏れ 🔴 0」の根拠** ─ activities 系は `ActivityController` / `ActivityCommentController` / `ActivityStatsController` / `ActivityTemplateController` / `ActivityPublicController` / `SystemActivityPresetController` の 6 コントローラで CRUD・参加者・コメント・統計・公開・テンプレート・公式プリセットを実装済み。設計書 F06.1 が CMS と活動記録の両機能を抱える「旧設計時代の単一文書」のままで、活動記録機能は F06.4 として独立しているのに F06.1 §4「活動記録」表が古いままになっている、というのが乖離の本質。
> 同様に action-memo 系も `ActionMemoController` / `ActionMemoTagController` / `ActionMemoSettingsController` / `ActionMemoDashboardController` の 4 コントローラで全機能が実装済み。設計書 F02.5 と F02.5_phase3 に複数回 path が言及されているための重複表示。

---

## 1. activities ドメイン（F06.1 + F06.4 + F04.10）

### 1.1 部 1: `/api/v1/activities/* (20 件)` path 単位 triage

#### A. activities CRUD 系（F06.4 と一致、F06.1 旧記載が冗長）

| path | 設計書 | 実装 | 判定 |
|---|---|---|---|
| `GET /api/v1/activities` | F06.1 729, 1680 / F06.4 316, 647, 903 | `ActivityController#listActivities` | 🟡 F06.1 §4 活動記録表全体を「F06.4 を参照」リダイレクト記述に簡素化、または旧表全削除 |
| `POST /api/v1/activities` | F06.1 730, 1458 / F06.4 317, 467, 562, 578 | `ActivityController#createActivity` | 🟡 同上 |
| `GET /api/v1/activities/{id}/comments` | F06.4 332 | `ActivityCommentController#listComments` | ⚪ 実装あり一致（scanner の `{_}` プレースホルダ展開で 1 行のみ漏れ。v6 課題） |
| `POST /api/v1/activities/{id}/comments` | F06.4 333 | `ActivityCommentController#createComment` | ⚪ 同上（v6 課題） |

> **F06.4 332/333 のコメント 2 件**: 実装側 `ActivityCommentController` の path は `/api/v1/activities/{activityId}/comments` で、scanner は `{_}/comments` 展開しているはずだが設計あり実装なし側に残置。`{activityId}` のような名前付き変数を `{_}` に潰すロジックで設計書側だけ取りこぼしている可能性。triage 上は実装一致と判定し、scanner v6 で根治する。

#### B. activities/custom-fields 系（F06.1 旧設計、Phase 6 で削除予定）

| path | 設計書 | 実装 | 判定 |
|---|---|---|---|
| `GET /api/v1/activities/custom-fields` | F06.1 738 | （実装なし） | 🟡 F06.1 §4 活動記録セクションから削除。**実装は `activity_templates` のフィールド定義として F06.4 へ移行済み** |
| `POST /api/v1/activities/custom-fields` | F06.1 739 | （実装なし） | 🟡 同上 |
| `PUT /api/v1/activities/custom-fields/{id}` | F06.1 740 | （実装なし） | 🟡 同上 |
| `DELETE /api/v1/activities/custom-fields/{id}` | F06.1 741 | （実装なし） | 🟡 同上 |

> **判断根拠**: F06.4 § DBスキーマで `activity_templates` + `activity_template_fields` 構成が定義されており、カスタムフィールドはテンプレート単位で定義する設計に統合されている。F06.1 が「活動記録のグローバル custom-fields」を仮定していた旧設計は機能廃止扱い。

#### C. activities/stats 系（F06.4 は実装あり、F06.1 にしかない 2 path は旧設計）

| path | 設計書 | 実装 | 判定 |
|---|---|---|---|
| `GET /api/v1/activities/stats/members/{userId}` | F06.1 743, 1789 | （実装なし） | 🟡 F06.1 §4 から削除。**個人別統計は F06.4 `/stats` の `participant_user_id` クエリで代替できる設計** |
| `GET /api/v1/activities/stats/ranking` | F06.1 744, 1845 | （実装なし） | 🟡 F06.1 §4 から削除。**フィールド別ランキングは F06.4 `/stats/fields` で代替（`aggregatable` フィールドの集計 API が実装済み）** |

> 補足: F06.4 設計書の `/stats` と `/stats/fields` は実装済み。F06.1 が独自に `stats/members` `stats/ranking` を仕様化していたが、F06.4 リファクタで吸収された。

#### D. activities/templates 系（F06.4 で `/activity-templates` へリファクタ済み）

| path | 設計書 | 実装 | 判定 |
|---|---|---|---|
| `GET /api/v1/activities/templates` | F06.1 750 | 実装は `/api/v1/activity-templates` (`ActivityTemplateController#listTemplates`) | 🟡 F06.1 §4 旧 URL を削除、または F06.4 への参照リンクへ書き換え |
| `POST /api/v1/activities/templates` | F06.1 751 | `POST /api/v1/activity-templates` (`ActivityTemplateController#createTemplate`) | 🟡 同上 |
| `GET /api/v1/activities/templates/{id}` | F06.1 752 | `GET /api/v1/activity-templates/{id}` (`ActivityTemplateController#getTemplate`) | 🟡 同上 |
| `PUT /api/v1/activities/templates/{id}` | F06.1 753 | `PUT /api/v1/activity-templates/{id}` (`ActivityTemplateController#updateTemplate`) | 🟡 同上 |
| `DELETE /api/v1/activities/templates/{id}` | F06.1 754 | `DELETE /api/v1/activity-templates/{id}` (`ActivityTemplateController#deleteTemplate`) | 🟡 同上 |
| `POST /api/v1/activities/templates/{id}/share` | F06.1 755, 2006 | （実装なし） | 🟡 F06.1 §4 から削除。**共有機能は F06.4 `/activity-templates/{id}/duplicate` でスコープ越境コピーする設計に変更されているため、`share` API は廃案** |
| `DELETE /api/v1/activities/templates/{id}/share` | F06.1 756, 2024 | （実装なし） | 🟡 同上（廃案） |
| `GET /api/v1/activities/templates/official` | F06.1 757, 1917 | （実装なし） | 🟡 F06.1 §4 から削除。**公式テンプレートは F06.4 `/system-admin/activity-template-presets` + `/activity-templates/import-preset` で代替**（SystemActivityPresetController で実装済み） |
| `POST /api/v1/activities/templates/import` | F06.1 758, 1961 | 実装は `POST /api/v1/activity-templates/import-preset` (`ActivityTemplateController#importPreset`) | 🟡 F06.1 §4 を F06.4 の `import-preset` 名称に揃える、または F06.1 から削除 |

> 補足: `ActivityTemplateController` には `POST /api/v1/activity-templates/{id}/duplicate` も実装あり（F06.4 §4 にも記載あり）。F06.1 設計には無いが、本 triage の対象外（実装あり・設計あり F06.4 で一致）。

#### E. activities/generate-from-schedule（F06.1 のみ記載、未実装）

| path | 設計書 | 実装 | 判定 |
|---|---|---|---|
| `POST /api/v1/activities/generate-from-schedule` | F06.1 735, 1524, 2480 | （実装なし） | 🟡 F06.1 §4 から削除。F06.4 設計書には記載なし。**スケジュール → 活動記録の自動生成は v1 スコープ外**（F03 スケジュールドメインとのイベント連携で別途軍議が必要） |

### 1.2 部 1: `/api/v1/activity-records/* (1 件)`（F04.10 由来）

| path | 設計書 | 実装 | 判定 |
|---|---|---|---|
| `POST /api/v1/activity-records` | F04.10 381 (`scope_type: 'COMMITTEE'` 受容) | 実装は `POST /api/v1/activities` (`ActivityController#createActivity` が `scope_type` クエリパラメータを受容) | 🟡 **F04.10 §「他機能との連携」表 381 行の URL を `/api/v1/activities` に修正**。`activity-records` は F06.4 開発初期の旧名称で、最終 URL は `activities` に決定されている |

### 1.3 部 1: `/api/v1/activity-templates/* (2 件)`（F06.4 由来）

| path | 設計書 | 実装 | 判定 |
|---|---|---|---|
| `GET /api/v1/activity-templates` | F06.4 306 | `ActivityTemplateController#listTemplates` | ⚪ 実装あり一致（v5 スコープ展開の集計で「設計あり実装なし」表に残置） |
| `POST /api/v1/activity-templates` | F06.4 307, 363, 450 | `ActivityTemplateController#createTemplate` | ⚪ 実装あり一致（同上） |

> 補足: F06.4 § リクエスト／レスポンス仕様セクションで `POST /api/v1/activity-templates` が複数の用例（363, 450 行など）で言及されており、scanner が path単位で集約しきれず「2 件」と表示。triage 上は実装一致として処理。

### 1.4 部 2（実装あり・設計なし）

activities ドメインに該当する `#### /api/v1/activities/*` セクションは存在しない（部 1 の 20 path はすべて旧設計・実装側に存在しないため）。一致判定された 11 path については baseline 一致セクションに収まっている。

---

## 2. action-memo 系（F02.5）

### 2.1 部 1: `/api/v1/action-memo-settings/* (2 件)`

| path | 設計書 | 実装 | 判定 |
|---|---|---|---|
| `GET /api/v1/action-memo-settings` | F02.5 230, 339 / F02.5_phase3 314 | `ActionMemoSettingsController#getSettings` | ⚪ 実装あり一致。設計書内で複数回 path が引用されており、scanner v5 が path 単位の集計に変換しきれていない |
| `PATCH /api/v1/action-memo-settings` | F02.5 231, 353, 612 / F02.5_phase3 315 | `ActionMemoSettingsController#updateSettings` | ⚪ 同上 |

> 取り扱い: scanner v6 の path 集約強化課題として記録。設計書側に変更は不要。

### 2.2 部 1: `/api/v1/action-memo-tags/* (2 件)`

| path | 設計書 | 実装 | 判定 |
|---|---|---|---|
| `GET /api/v1/action-memo-tags` | F02.5 226 | `ActionMemoTagController#listTags` | ⚪ 実装あり一致（path 単位） |
| `POST /api/v1/action-memo-tags` | F02.5 227, 611 | `ActionMemoTagController#createTag` | ⚪ 同上 |

> 取り扱い: 同上、scanner v6 課題。

### 2.3 部 1: `/api/v1/action-memos/* (2 件)`

| path | 設計書 | 実装 | 判定 |
|---|---|---|---|
| `GET /api/v1/action-memos` | F02.5 218, 377 / F02.5_phase3 311 | `ActionMemoController#listMemos` | ⚪ 実装あり一致 |
| `POST /api/v1/action-memos` | F02.5 217, 239, 609, 640 / F02.5_phase3 229, 309, 465 | `ActionMemoController#createMemo` | ⚪ 同上 |

> 取り扱い: 同上、scanner v6 課題。

### 2.4 部 2: `/api/v1/action-memos/* (4 件)`（実装あり・設計なし）

| path | 実装 | 設計書 | 判定 |
|---|---|---|---|
| `DELETE /api/v1/action-memos/{id}/complete-todo` | `ActionMemoController#revertTodoCompletion` (304) | F02.5_phase3 §4 で `completes_todo` フラグ仕様あり、API URL は未記載 | 🟡 **F02.5_phase3 §6.1 エンドポイント一覧に `DELETE /api/v1/action-memos/{id}/complete-todo`（completes_todo フラグの取り消し）を追記** |
| `GET /api/v1/action-memos/available-orgs` | `ActionMemoController#getAvailableOrgs` (252) | F02.5_phase3 §6.1 に `available-teams` は記載あり、`available-orgs` は記載なし | 🟡 **F02.5_phase3 §6.1 に `GET /api/v1/action-memos/available-orgs`（投稿先として選択可能な組織一覧）を追記**。組織タイムラインへの投稿対応で実装が先行している |
| `GET /api/v1/action-memos/mood-stats` | `ActionMemoController#getMoodStats` (271) | F02.5 §「メトリクス」に mood 関連 metric の記載はあるが API は未記載 | 🟡 **F02.5 §4 エンドポイント一覧に `GET /api/v1/action-memos/mood-stats`（自分の気分統計の取得）を追記** |
| `GET /api/v1/action-memos/{id}/audit-logs` | `ActionMemoController#getMemoAuditLogs` (288) | F02.5 § 監査ログ仕様に記載はあるが API は未記載 | 🟡 **F02.5 §4 エンドポイント一覧に `GET /api/v1/action-memos/{id}/audit-logs`（特定メモの操作履歴取得）を追記** |

---

## 3. 関連設計書の他ドメイン由来 path（参考）

本 triage では path 自体は触れず、各ドメインの triage 担当に委ねる。記録のみ。

| 表所属 | path | 言及設計書 | 推奨対応 |
|---|---|---|---|
| `/api/v1/teams/*` | `GET /api/v1/teams/{teamId}/members/{memberId}/action-memos` | （F02.5 / F02.5_phase3 のいずれかに記載される想定） | `ActionMemoDashboardController#getMemberMemos` (`/api/v1/teams/{teamId}/members/{memberId}/action-memos`) が実装済み。teams ドメインの triage 担当（足軽3-α）に委任 |

---

## 4. 設計書編集計画

### 4.1 F06.1_cms_blog.md（活動記録セクション）

**方針**: F06.1 は元々「CMS（ブログ・お知らせ）」を主機能とする文書だったが、Phase 6 リファクタで活動記録機能が F06.4 として独立した。F06.1 §4 内の「#### 活動記録」「#### 活動記録テンプレート」の 2 表（合計 30 行強）は重複かつ旧設計を含むため、以下のように整理する。

1. **§4「#### 活動記録」表（729〜745 行）**: 表全体を削除し、以下のリダイレクト記述に置換する:

   ```markdown
   #### 活動記録

   > **本機能は F06.4「活動記録」へ機能分離されました。** API 仕様は
   > [docs/features/F06.4_activity_records.md](./F06.4_activity_records.md) §4「エンドポイント一覧」を参照してください。
   >
   > F06.1 (CMS) は活動記録機能を扱わなくなりました（Phase 6 リファクタ時点）。
   ```

2. **§4「#### 活動記録テンプレート」表（748〜762 行）**: 同様に削除しリダイレクト記述に置換する。`/api/v1/activities/templates/*` 系の旧 URL は **F06.4 の `/api/v1/activity-templates/*` に統一済み** である旨を明記。

3. **§4 リクエスト／レスポンス仕様セクション中の活動記録関連サブセクション**: 該当する個別仕様（行 1458, 1524, 1680, 1789, 1845, 1917, 1961, 2006, 2024, 2480 など）を削除し、F06.4 への参照に置換する。本 PR では「§4 一覧表」と「冒頭サマリ」の更新に留め、リクエスト本文中の URL 引用書き換えは TODO コメントで残す（変更行数膨大のため）。

4. **§3 DB スキーマ章内の活動記録テーブル定義**: `activity_results` / `activity_templates` / `activity_template_fields` の DDL 章は F06.4 へ移譲されているため、F06.1 から削除（または F06.4 への参照）に変更。本 PR の範囲外として TODO として記録。

### 4.2 F04.10_committee.md

**§「他機能との連携」表 381 行**: `POST /api/v1/activity-records` → `POST /api/v1/activities` に書き換え。コメント文も「`scope_type: 'COMMITTEE'` 受容（実装は `/api/v1/activities` で活動記録作成 API を共有）」へ更新。

### 4.3 F02.5_action_memo.md

**§4 エンドポイント一覧表**: 現在 16 行。以下 2 行を追記:

```markdown
| GET | `/api/v1/action-memos/mood-stats` | 必要 | 自分の気分（mood）の統計取得（mood_enabled 利用者向け） |
| GET | `/api/v1/action-memos/{id}/audit-logs` | 必要 | 特定メモの操作履歴取得（IDOR 検証あり） |
```

### 4.4 F02.5_phase3_team_timeline_and_todo_link.md

**§6.1 エンドポイント一覧表**: 現在 8 行。以下 2 行を追記:

```markdown
| DELETE | `/api/v1/action-memos/{id}/complete-todo` | 新規 | `completes_todo=true` で関連 TODO を完了化した操作を取り消す（TODO を OPEN へ戻す） |
| GET | `/api/v1/action-memos/available-orgs` | 新規 | 投稿先として選択可能な組織一覧（チーム以外に組織タイムラインへも投稿可能） |
```

### 4.5 docs/internal/api_drift_exclusions.yml

activities / action-memo 系で除外パターン追加候補なし。すべて B 案（実装も設計書化対象）方針で、設計書を更新して整合させる。

---

## 5. PR スコープ

本 PR では以下を実施:

- [x] F06.1 §4 活動記録 2 表（活動記録 / 活動記録テンプレート）を F06.4 へのリダイレクト記述に置換
- [x] F04.10 §「他機能との連携」表 381 行の URL を `activity-records` → `activities` に修正
- [x] F02.5 §4 エンドポイント一覧表に `mood-stats` / `audit-logs` 2 行を追記
- [x] F02.5_phase3 §6.1 エンドポイント一覧表に `complete-todo` (DELETE) / `available-orgs` 2 行を追記
- [x] `docs/internal/triage_log/activities.md` 新規作成（本ファイル）

## 6. 残課題（次 PR 候補）

- F06.1 §4 リクエスト／レスポンス仕様サブセクション本文中の活動記録関連 URL 引用全削除（変更行数膨大のため別 PR で実施）
- F06.1 §3 DB スキーマ章の `activity_*` テーブル DDL を F06.4 への参照に移譲（同上、別 PR）
- F06.1 §「活動記録」関連の図表（ER 図・シーケンス図等）の F06.4 への移譲（同上）
- scanner v5 の path 単位重複排除強化（action-memo* 系の偽陽性根治）→ scanner v6 課題
- scanner v5 の `{_}` プレースホルダ正規化強化（`/activities/{id}/comments` 行の漏れ）→ 同 scanner v6 課題

---

## 7. 判断に迷った点

1. **F06.1 活動記録セクションを完全削除するか、F06.4 へのリダイレクト記述に置換するか**:
   F06.1 は外部公開 SSR ブログ・お知らせの主要設計書として現役。活動記録部分だけが旧設計のまま残されている状態。完全削除すると F06.1 を読む人が「活動記録仕様はどこ？」と迷うため、**リダイレクト記述で残す方針**を採用。本 PR では §4 表 2 つをリダイレクト記述に置換し、リクエスト／レスポンス仕様サブセクション本文は次 PR の TODO として残置する。

2. **F04.10 の `/api/v1/activity-records` を `activity-records` 命名のまま尊重するか、`activities` に書き換えるか**:
   実装が `/api/v1/activities` で稼働中。フロントエンドも `/api/v1/activities` で叩いている前提。F04.10 設計書を実装に合わせる方針（既存挙動を壊さない原則）。

3. **F02.5 拡張 4 API（`mood-stats` 等）を F02.5 と F02.5_phase3 のどちらに追記するか**:
   - `mood-stats` `audit-logs` は **F02.5 本体** の機能（Phase 1 / Phase 2 で追加された監査・統計）に位置付けられるため F02.5 へ追記
   - `complete-todo` `available-orgs` は **F02.5 Phase 3** の機能（チームタイムライン投稿・TODO 連携の派生）として F02.5_phase3 へ追記
   - 一律 F02.5 にまとめる選択肢もあったが、Phase 区分が混ざると将来の機能追加時に追跡しにくくなるため分離

4. **`available-orgs` を `available-teams` と統合するか、別 API として残すか**:
   実装は `getAvailableTeams` と `getAvailableOrgs` が別エンドポイントとして分離されている。組織タイムラインとチームタイムラインで投稿先選択 UI が分かれているため、API も分離した方が自然と判断し、別エントリで設計書追記。実装統合の必要性が将来出れば別軍議で対応。

5. **`activities/{id}/comments` の scanner v5 偽陽性をどう扱うか**:
   設計書 F06.4 332/333 行に `GET /api/v1/activities/{id}/comments` `POST /api/v1/activities/{id}/comments` が明記され、実装も `ActivityCommentController` で完備。v5 scanner の `{_}` 展開ロジックで設計書側だけが取りこぼされた可能性が高い。本 triage では実装一致と判定し、scanner v6 課題（path プレースホルダ正規化強化）として記録する。設計書側の修正は不要。
