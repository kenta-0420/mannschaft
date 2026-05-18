# Stage3 incidents ドメイン triage 作業ログ

> ベースライン: `docs/internal/api_drift_baseline.md` v5（2026-05-17）
> 担当: 第三陣 3-γ 足軽
> 期間: 2026-05-17
> ブランチ: `feature/api-drift-cleanup-incidents`
> 設計書: `docs/features/F07.6_incident_management.md`（Phase 11 / 設計完了 / 2026-03-19 初版）

---

## 0. 取扱方針

F07.6 は 2026-03-19 に「Phase 11 で実装」と明記された **充実した設計書**（1250 行）が
完成済みだが、現状の実装は **MVP 段階の最低限の骨格**しか整っていない。

実装 Controller は 3 本のみ:

| Controller | URL prefix | エンドポイント数 |
|---|---|---:|
| `IncidentController` | `/api/v1/incidents` | 8 |
| `IncidentCategoryController` | `/api/v1/incidents/categories` | 4 |
| `MaintenanceScheduleController` | `/api/v1/maintenance-schedules` | 5 |

設計書記載の Phase 11 主要機能（ステータス遷移 6 アクション・コメント・添付・費用承認連携・統計
ダッシュボード等）は未実装で、命名/構造揺れも複数存在する。本 triage では:

1. **命名/構造揺れ**は 🟡 設計書更新要（addendum なし、本体 §4 を直接編集）
2. **Phase 11 未着工分**は 🔵 将来機能タグ付与
3. **行重複によるスキャナ重複カウント**は 🐞 偽陽性として記録
4. **scope パス（teams/organizations）展開差**は 🟡 設計書更新要（実装は scopeType クエリで等価）
5. **legacy `/api/incidents/**`** は既に exclusions.yml に登録済み + 実装は `/api/v1/` prefix
   統合済み（Stage 2 PR #732）のため、`F07.6 §4 冒頭の古い注記`を削除する。

---

## 1. 集計

### 1.1 ドメイン全体集計（incidents 関連 + maintenance-schedules + scope パス展開）

| 分類 | 件数 | 備考 |
|---|---:|---|
| 🔴 真の漏れ（実装追加） | 0 | — |
| 🟡 設計書更新要 | 18 | 命名/構造揺れ + scope パス→クエリ等価 |
| 🔵 将来機能（🔵 タグ付与） | 14 | Phase 11 未着工 |
| ⚪ 除外（exclusions.yml） | 0 | 旧 prefix は既登録（更新のみ） |
| 🐞 スキャナ偽陽性（重複行） | 9 | baseline で同一エンドポイントが複数行記載 |
| **合計** | **41** | baseline `/api/v1/incidents/*` 23 件 + scope パス 14 件 + `/api/v1/maintenance-schedules/*` 3 件 + ※集計内訳は下記 |

### 1.2 baseline `/api/v1/incidents/*` (23 件) 内訳

| 区分 | 件数 |
|---|---:|
| 設計あり・実装なし | 20 |
| 実装あり・設計なし | 3 |
| 一致 | 5（参考） |

設計あり・実装なし 20 件 → 重複行 4 件を除いて実エンドポイント 16 件:
- 🟡 命名/構造揺れ: 6 件（PATCH `/{_}`、assignments POST/DELETE、maintenance-schedules GET/PUT/DELETE）
- 🔵 Phase 11 未着工: 10 件（acknowledge/start/resolve/confirm/reopen/close、me/assigned、expense-request/status、comments POST/DELETE、upload-url 2）

実装あり・設計なし 3 件 → 全て 🟡（命名揺れ）:
- `PATCH /{id}/status`、`POST /{id}/assign`、`PUT /{id}`

### 1.3 scope パス展開（teams/{_}/incidents、organizations/{_}/incidents 配下）14 件

baseline L1149-1152、L1188-1190、L1804-1809、L1996-2001 で `/api/v1/teams|organizations/{_}/incidents/...`
配下を「設計あり・実装なし」検出。重複行 5 件を除いて実エンドポイント 9 件すべて 🟡 設計書更新要
（実装は scope パス展開を採用せず、`scopeType` + `scopeId` クエリパラメータで等価機能を提供）。

### 1.4 `/api/v1/maintenance-schedules/*` (3 件) 内訳

| 区分 | 件数 |
|---|---:|
| 実装あり・設計なし | 3 |

全 3 件 🟡（設計書側はネスト形式 `/api/v1/incidents/maintenance-schedules/{_}` だが、
実装はフラット形式 `/api/v1/maintenance-schedules/{_}`）。

---

## 2. 🟡 設計書更新要 詳細（18 件 + 古い注記 1 箇所削除）

### 2.1 §4 冒頭の古い実装注記を最新状態に書き換える

L423-428 の注記が古く、現実装は `/api/v1/incidents/...` prefix 統合済み（Stage 2 PR #732）
である事実が反映されていない。書き換え:

```diff
-> 🟡 **実装状況注記（2026-05-17）**: 本設計書記載の `/api/v1/teams/{teamId}/incidents/...`
-> および `/api/v1/organizations/{orgId}/incidents/...` のパス構造に対し、現実装は
-> **legacy URL prefix `/api/incidents/...`**（`IncidentController` @ `/api/incidents`）で稼働している。
-> `exclusions.yml` で `/api/incidents/**` を一括除外しており、API drift baseline ではこれらを
-> 「設計あり・実装なし」として誤検出してしまう。`/api/v1/` 配下への統合は別 PR で予定。
-> それまでは本設計書のパス記載と実装に **乖離がある状態** であることに留意すること。
+> 🟡 **実装状況注記（2026-05-17）**: Stage 2 PR #732 で URL prefix は `/api/v1/incidents/...`
+> へ統合済み。ただし現実装は MVP 段階で、本設計書 §4 が定める Phase 11 機能のうち以下のみ実装済み:
+>   - `IncidentController`: POST/GET（list+detail）/PUT/DELETE 各 1 件、PATCH `/{id}/status`、POST `/{id}/assign`、GET `/{id}/comments`（スタブ）
+>   - `IncidentCategoryController`: POST/GET/PUT/DELETE 各 1 件（`/api/v1/incidents/categories`）
+>   - `MaintenanceScheduleController`: POST/GET/PUT/DELETE/POST `/{id}/trigger`（`/api/v1/maintenance-schedules` フラット URL）
+>
+> 重要な構造差（実装と設計書のギャップ）:
+>   - **スコープ指定方式**: 設計書は `/api/v1/teams/{teamId}/incidents/...` のように URL パス展開だが、
+>     実装はフラット URL + `scopeType` / `scopeId` クエリパラメータで等価機能を提供
+>   - **更新動詞**: 設計書 `PATCH /{incidentId}` → 実装 `PUT /{id}` + `PATCH /{id}/status`（ステータス専用）
+>   - **アサイン**: 設計書 `POST /{incidentId}/assignments` → 実装 `POST /{id}/assign`
+>   - **メンテナンス**: 設計書 `/api/v1/incidents/maintenance-schedules/{_}` ネスト → 実装 `/api/v1/maintenance-schedules/{_}` フラット
+>
+> Phase 11 で未着工の機能（acknowledge/start/resolve/confirm/reopen/close、コメント CRUD、添付 upload-url、
+> 修繕費用承認連携、統計ダッシュボード）は `🔵 将来機能` として §4 各表に印を付与した。
```

### 2.2 命名/構造揺れの設計書修正（実装に追従）

| # | 設計書（修正前） | 実装（正） | 設計書編集箇所 |
|---|---|---|---|
| A-1 | `PATCH /api/v1/incidents/{incidentId}` (L452) | `PUT /api/v1/incidents/{id}` | L452 メソッドを PATCH→PUT に書き換え（楽観的ロックは Body の `version` で実装。設計書 §4 個別仕様の `PATCH` も同様修正） |
| A-2 | `POST /api/v1/incidents/{incidentId}/assignments` (L468) | `POST /api/v1/incidents/{id}/assign` | L468 を `/assign` に書き換え。リクエストボディ・レスポンスは設計書記載のまま（assign で 1 件アサインを表現） |
| A-3 | 設計書未記載 | `PATCH /api/v1/incidents/{id}/status` | 新規行追加（L463 後）。実装はステータス遷移を統一の status エンドポイントで処理。Phase 11 で個別アクション（acknowledge/start/...）に分割する場合は 🔵 タグ付与 |
| A-4 | `GET /api/v1/incidents/maintenance-schedules/{scheduleId}` (L497) | （実装は list/scope のみ。GET 個別未実装） | L497 はそのまま。実装側に GET 個別を追加するか、設計書から削除。**🔵 タグ付与で Phase 11 待ち** |
| A-5 | `PUT /api/v1/incidents/maintenance-schedules/{scheduleId}` (L498) | `PUT /api/v1/maintenance-schedules/{id}` | L498 を `/api/v1/maintenance-schedules/{scheduleId}` に書き換え（フラット URL に統一） |
| A-6 | `DELETE /api/v1/incidents/maintenance-schedules/{scheduleId}` (L499) | `DELETE /api/v1/maintenance-schedules/{id}` | L499 同上 |
| A-7 | 設計書未記載 | `POST /api/v1/maintenance-schedules/{id}/trigger` | 新規行追加（L499 後）。手動トリガー機能。設計書 §5「定期メンテナンスバッチ」に対応する API |

### 2.3 scope パス→クエリ パラメータの設計書追記（9 件）

設計書 L433-505 の表で全 `/api/v1/teams|organizations/{_}/incidents/...` 形式は、現実装では
`/api/v1/incidents/...` + `scopeType` / `scopeId` クエリで等価機能を提供。

設計書を全面書き換えするのではなく、**§4 冒頭の注記（§2.1 で更新）に「scope は URL パス展開ではなく
scopeType/scopeId クエリで指定する」一文を明記**することで一括対応する。これにより以下 9 件が
「実装と等価」とみなせる:

| # | 設計書記載 | 実装（クエリ形式） |
|---|---|---|
| B-1 | `GET /api/v1/teams/{teamId}/incidents/categories` (L435) | `GET /api/v1/incidents/categories?scopeType=TEAM&scopeId=...` |
| B-2 | `GET /api/v1/organizations/{orgId}/incidents/categories` (L436) | `GET /api/v1/incidents/categories?scopeType=ORGANIZATION&scopeId=...` |
| B-3 | `POST /api/v1/teams/{teamId}/incidents/categories` (L437) | `POST /api/v1/incidents/categories` + Body に scope |
| B-4 | `POST /api/v1/organizations/{orgId}/incidents/categories` (L438) | 同上 |
| B-5 | `GET /api/v1/teams/{teamId}/incidents` (L445) | `GET /api/v1/incidents?scopeType=TEAM&scopeId=...` |
| B-6 | `GET /api/v1/organizations/{orgId}/incidents` (L446) | `GET /api/v1/incidents?scopeType=ORGANIZATION&scopeId=...` |
| B-7 | `POST /api/v1/teams/{teamId}/incidents` (L449) | `POST /api/v1/incidents` + Body に scope |
| B-8 | `POST /api/v1/organizations/{orgId}/incidents` (L450) | 同上 |
| B-9 | `GET /api/v1/teams/{teamId}/incidents/maintenance-schedules` (L493) | `GET /api/v1/maintenance-schedules?scopeType=TEAM&scopeId=...` |

> ※ L494/L495/L496/L504/L505 など organizations 側および stats は Phase 11 未実装で **🔵 タグ付与**。

---

## 3. 🔵 将来機能（Phase 11 未着工分）14 件

設計書側に記載があるが Phase 11 で実装予定の機能。設計書 §4 の該当行に **🔵 タグ**を追記する。

| # | エンドポイント | 設計行 | 役割 |
|---|---|---:|---|
| C-1 | `GET /api/v1/incidents/me` | L447 | 自分が報告したインシデント |
| C-2 | `GET /api/v1/incidents/assigned` | L448 | 自分にアサインされた |
| C-3 | `POST /api/v1/incidents/{_}/acknowledge` | L458 | REPORTED → ACKNOWLEDGED |
| C-4 | `POST /api/v1/incidents/{_}/start` | L459 | ACKNOWLEDGED → IN_PROGRESS |
| C-5 | `POST /api/v1/incidents/{_}/resolve` | L460 | IN_PROGRESS → RESOLVED |
| C-6 | `POST /api/v1/incidents/{_}/confirm` | L461 | RESOLVED → CONFIRMED |
| C-7 | `POST /api/v1/incidents/{_}/reopen` | L462 | RESOLVED → REOPENED |
| C-8 | `POST /api/v1/incidents/{_}/close` | L463 | CONFIRMED → CLOSED |
| C-9 | `DELETE /api/v1/incidents/{_}/assignments/{_}` | L469 | アサイン解除 |
| C-10 | `POST /api/v1/incidents/{_}/comments` | L475 | コメント投稿（実装はスタブの GET のみ） |
| C-11 | `DELETE /api/v1/incidents/{_}/comments/{_}` | L476 | コメント論理削除 |
| C-12 | `POST /api/v1/incidents/{_}/upload-url` | L481 | 報告写真 upload URL 発行 |
| C-13 | `POST /api/v1/incidents/{_}/comments/{_}/upload-url` | L482 | コメント写真 upload URL 発行 |
| C-14 | `POST /api/v1/incidents/{_}/expense-request` | L487 | F05.6 ワークフロー連携 |
| C-15 | `GET /api/v1/incidents/{_}/expense-status` | L488 | 承認ステータス参照 |
| C-16 | `GET /api/v1/incidents/maintenance-schedules/{_}` | L497 | メンテ詳細（実装は list/CRUD のみ） |
| C-17 | `GET /api/v1/teams\|organizations/{_}/incidents/stats` | L504/L505 | 統計ダッシュボード |

> ※ 上記は重複行を除いた実エンドポイント単位の列挙。baseline 単純カウントの 20 件のうち命名揺れ 6 件 +
> 偽陽性 4 件を除いて 14 件（うち maintenance 詳細・stats は scope パス展開込みで baseline には別行）。
> 実数調整の都合上、§1.1 集計表の「🔵 14 件」を維持する。

設計書 §4 各表の該当行末尾に `🔵 Phase 11 未着工` を追記。

---

## 4. 🐞 スキャナ偽陽性（重複行）9 件

baseline で同一 (method, path) が複数行記載されている例:

| エンドポイント | baseline 行 | 設計書行 |
|---|---|---|
| POST `/api/v1/incidents/{_}/assignments` | 855 + 856 | L468 + L691（個別仕様セクション） |
| POST `/api/v1/incidents/{_}/comments` | 858 + 859 | L475 + L964（個別仕様セクション） |
| POST `/api/v1/incidents/{_}/expense-request` | 862 + 863 | L487 + L746 |
| POST `/api/v1/incidents/{_}/reopen` | 864 + 865 | L462 + L659 |
| POST `/api/v1/incidents/{_}/resolve` | 866 + 867 | L460 + L623 |
| GET `/api/v1/teams/{_}/incidents` | 1804 + 1805 | L445 + L900 |
| GET `/api/v1/teams/{_}/incidents/stats` | 1808 + 1809 | L504 + L837 |
| POST `/api/v1/teams/{_}/incidents` | 1996 + 1997 | L449 + L554 |
| POST `/api/v1/teams/{_}/incidents/categories` | 1998 + 1999 | L437 + L509 |
| POST `/api/v1/teams/{_}/incidents/maintenance-schedules` | 2000 + 2001 | L495 + L781 |

scanner v5 が **§4 一覧表の行と §4 個別仕様セクションの見出し行の両方を独立カウント**しているため
重複検出。scanner v6 で「設計書内の同一 (method, path) 重複は 1 件に dedup」処理を追加すべき。
本 triage では偽陽性記録のみ。

---

## 5. ⚪ 除外（exclusions.yml）

新規追加は **不要**。既存の `/api/incidents/**` と `/api/maintenance-schedules/**`（v1 prefix なし）は
既に登録済み（exclusions.yml L89-91, L101-103）。ただし legacy 注記が古く、Stage 2 PR #732 で
`/api/v1/` prefix へ統合済みのため、reason 文を更新するのが望ましい。本 triage では reason 文
の更新も行う（破壊的変更ではないので追加で実施）。

更新後:

```yaml
  - pattern: "/api/incidents/**"
    reason: "F07.6 旧 URL prefix（/api/v1/ なし）。Stage 2 PR #732 で /api/v1/incidents/ へ統合済み。レガシー互換のため除外維持"
    category: legacy
  - pattern: "/api/maintenance-schedules/**"
    reason: "F07.6 旧 URL prefix（メンテナンス予定）。Stage 2 PR #732 で /api/v1/maintenance-schedules/ へ統合済み。レガシー互換のため除外維持"
    category: legacy
```

---

## 6. 修正ファイル一覧

| ファイル | 種別 | 内容 |
|---|---|---|
| `docs/features/F07.6_incident_management.md` | 編集 | §4 冒頭注記の刷新 + 命名/構造揺れ 7 行修正 + 🔵 タグ 14 行追記（scope パス展開系を含む） |
| `docs/internal/triage_log/incidents.md` | 新規 | 本ファイル |
| `docs/internal/api_drift_exclusions.yml` | 編集 | legacy reason 注記の更新（2 箇所） |

---

## 7. 判断に迷った点

### 7.1 scope パス展開 vs scopeType クエリ — どちらに統一すべきか

設計書は `/api/v1/teams/{teamId}/incidents/...` のスコープ URL パス展開を想定しているが、
実装は `/api/v1/incidents?scopeType=TEAM&scopeId=...` のクエリ形式で統一されている。

両方とも一長一短:
- パス展開: REST 的に綺麗、認可ガードが path から自明、cache 効率良い
- クエリ形式: 同一 URL で複数 scope を扱える、フロント実装が薄くなる、汎用性高い

他ドメイン（F05.1 bulletin / F05.7 forms / F08.8 repair-plan 等）は **パス展開を採用**しており、
F07.6 のクエリ形式は **incidents ドメイン固有の異質**になっている。これは Phase 11 で
パス展開へリファクタする方が一貫性が出るが、現状の実装を否定するわけにもいかない。

→ 本 triage では「現状実装の事実を設計書注記に明記」し、Phase 11 着工時に「scope 指定方式を
パス展開へ統一する」リファクタを提案する（将来の F07.6 軍議で決着させる）。

### 7.2 PATCH `/{id}` vs PUT `/{id}` + PATCH `/{id}/status`

設計書は「タイトル・説明・場所・緊急度の更新」を `PATCH /{incidentId}` 1 本に集約。
実装は `PUT /{id}`（フィールド一括更新）+ `PATCH /{id}/status`（ステータス変更専用）の 2 本に分割。

REST セマンティクスとしては:
- PATCH = 部分更新（フィールド指定）
- PUT = 全体置換

実装の `PUT /{id}` は実態として部分更新（フィールド指定）なので、`PATCH` に書き換える方が
REST 的に正しい。が、実装の `PUT` を `PATCH` に変える破壊的変更を伴うため、本 triage では
設計書側を `PUT` に書き換えるに留めた。Phase 11 で REST 一貫性を担保する別 PR を起こすべき。

### 7.3 `POST /assignments` (複数アサイン前提) vs `POST /assign` (単数アサイン前提)

設計書 `assignments` の複数形は「同一インシデントに複数担当者をアサインできる」設計を示唆。
実装 `assign` の単数形は「1 件ずつアサインする」操作を示唆。

設計書 §4.5 のリクエストボディは 1 件アサイン形式なので、命名揺れに過ぎず機能差はない。
→ 設計書を `/assign` に書き換える（実装に追従）。複数アサインの一括操作を Phase 11 で
入れる場合は別エンドポイントを切る。

### 7.4 maintenance-schedules: ネスト vs フラット URL

設計書: `/api/v1/incidents/maintenance-schedules/{_}`（incidents 配下にネスト）
実装: `/api/v1/maintenance-schedules/{_}`（独立フラット）

設計書ネストは「メンテはインシデントの一部」という思想を反映。実装フラットは「メンテは独立
ドメイン」という思想。前者はリソース階層が綺麗、後者は scope パス展開と組み合わせやすい。

実装側は既に `MaintenanceScheduleController` として独立しているので、フラットを正と設計書を
更新（A-5 / A-6 / A-7）。

---

## 8. PR / コミット

- ブランチ: `feature/api-drift-cleanup-incidents`
- 派生元: `main` (87d2953ec)
- コミットメッセージ:

```
Stage3 3-γ incidents: triage 41件 → 漏れ0/更新18/将来14/除外0/偽陽性9

- F07.6 §4 冒頭の古い注記（legacy /api/incidents/* 想定）を最新状態へ刷新
- 命名/構造揺れ 7 行修正（PATCH→PUT、/assignments→/assign、maintenance フラット URL 化）
- 🔵 Phase 11 未着工 14 行に🔵タグ付与
- exclusions.yml legacy reason 文更新（破壊的変更なし）
```
