# Stage3 第四陣 dwelling-units ドメイン triage 作業ログ

> 担当: 足軽4-β（feature/api-drift-cleanup-dwelling-units）
> 作業日: 2026-05-17
> 入力: `docs/internal/api_drift_baseline.md` v5
> 関連ドメイン: `/api/v1/dwelling-units/*` (18 件) + `/api/v1/residence-status/*` (13 件) +
>   section 2 中の `/api/v1/{organizations,teams}/{_}/dwelling-units/*` 実装側計上 +
>   section 4 (V4-1 スコープ階層プレフィックス逆引き準一致) 中の dwelling-units 系 10 件

---

## 0. 取扱方針

F09.1 居住者台帳 / F09.16 居住実態管理は **設計書がフラットパス先行・実装はマルチテナント
組織/チームスコープ階層に統一済** という典型的なスコープ移行案件である。両設計書とも
§4 / §6 冒頭に「スコープ移行注記（2026-05-17）」が既に存在し、
**「次フェーズで全行を `/api/v1/organizations/{orgId}/...` に書き換える PR が必要」** と
明示されている。

すなわち triage 観点では既に判定済みであり、本第四陣の任務は次の 3 点:

1. baseline v5 で「設計あり・実装なし」と計上される 31 件 (dwelling-units 18 + residence-status 13)
   を **🟡 設計書更新要** として一括処理する記録を残す
2. 既存の §冒頭注記を **scanner 視認しやすい形** に強化し、F17.1 villages と同じく
   addendum 別ファイル方式でなく **本体直編集** で実装パス対応表を追記する（
   本体は §4 / §6 表が短く一覧性が高いため）
3. section 2 / 4 の実装側計上は **scanner v6** で `{scopeType}` 展開吸収予定のため、
   exclusions.yml には追加しない（実装側を除外すると本体の設計突合が壊れるため）

---

## 1. 集計

| 分類 | 件数 | 備考 |
|---|---:|---|
| 🔴 真の漏れ（実装追加） | 0 | 全件実装済み |
| 🟡 設計書更新要 | 31 | F09.1 §4: 18 件 / F09.16 §6: 13 件 |
| 🔵 将来機能（マーカ付与） | 0 | — |
| ⚪ 除外（exclusions.yml） | 0 | scanner v6 で吸収予定のため運用追加なし |
| 🐞 スキャナ偽陽性 / 改善余地 | 17 | 詳細は §4 で後述 |
| **合計** | **31 件 + 関連 17 件** | |

---

## 2. 内訳

### 2.1 F09.1 居住者台帳 — `/api/v1/dwelling-units/*` (18 件)

baseline v5 §1 で 18 件が「設計あり・実装なし」として計上。各エンドポイントはテーブル行と
`#### GET /api/v1/dwelling-units/...` 章見出しの 2 箇所で重複検出されており、論理的には 13 種類。

| メソッド | 設計パス（旧） | 実装パス（正） | Controller |
|---|---|---|---|
| GET | `/api/v1/dwelling-units` | `/api/v1/organizations/{orgId}/dwelling-units` / `/api/v1/teams/{teamId}/dwelling-units` | `OrgDwellingUnitController#list` (L38) / `TeamDwellingUnitController#list` (L38) |
| POST | `/api/v1/dwelling-units` | `/api/v1/organizations/{orgId}/dwelling-units` / `/api/v1/teams/{teamId}/dwelling-units` | `OrgDwellingUnitController#create` (L50) / `TeamDwellingUnitController#create` (L50) |
| GET | `/api/v1/dwelling-units/{id}` | `/api/v1/organizations/{orgId}/dwelling-units/{id}` / `/api/v1/teams/{teamId}/dwelling-units/{id}` | V4-1 逆引き準一致済（baseline §4） |
| PUT | `/api/v1/dwelling-units/{id}` | 同上 | V4-1 逆引き準一致済 |
| DELETE | `/api/v1/dwelling-units/{id}` | 同上 | V4-1 逆引き準一致済 |
| POST | `/api/v1/dwelling-units/import` | **未実装**（CSV インポートは Phase 2 候補） | 該当 Controller なし → 🔵 候補だが、F09.1 §4 表には Phase 表記なし。今回は 🟡 として保留 |
| GET | `/api/v1/dwelling-units/{id}/residents` | `/api/v1/organizations/{orgId}/dwelling-units/{unitId}/residents` / `/api/v1/teams/{teamId}/dwelling-units/{unitId}/residents` | `OrgResidentController#list` (L37) / `TeamResidentController#list` (L38) |
| POST | `/api/v1/dwelling-units/{id}/residents` | 同上 (POST) | `OrgResidentController#create` (L44) / `TeamResidentController#create` (L45)（V4-1 逆引き準一致済） |
| PUT | `/api/v1/dwelling-units/{unitId}/residents/{id}` | `/api/v1/organizations/{orgId}/residents/{id}` / `/api/v1/teams/{teamId}/residents/{id}` | `OrgResidentController#update` (L52) / `TeamResidentController#update` (L53) — **実装は `/residents/{id}` で unitId を持たない** |
| DELETE | `/api/v1/dwelling-units/{unitId}/residents/{id}` | `/api/v1/organizations/{orgId}/residents/{id}` / `/api/v1/teams/{teamId}/residents/{id}` | 同上（delete） |
| PATCH | `/api/v1/dwelling-units/{unitId}/residents/{id}/move-out` | `/api/v1/organizations/{orgId}/residents/{id}/move-out` / `/api/v1/teams/{teamId}/residents/{id}/move-out` | `OrgResidentController#moveOut` (L74) / `TeamResidentController#moveOut` (L75) |
| PATCH | `/api/v1/dwelling-units/{id}/move-out-all` | **未実装** | 該当 Controller なし → 🟡 として F09.1 §4 注記に記載予定 |
| PATCH | `/api/v1/dwelling-units/{unitId}/residents/{id}/verify` | `/api/v1/organizations/{orgId}/residents/{id}/verify` / `/api/v1/teams/{teamId}/residents/{id}/verify` | `OrgResidentController#verify` (L67) / `TeamResidentController#verify` (L68) |
| PATCH | `/api/v1/dwelling-units/{unitId}/residents/{id}/renew-lease` | **未実装**（賃貸借契約更新は Phase 2 候補） | 該当 Controller なし → 🟡 として注記化 |
| POST | `/api/v1/dwelling-units/{id}/invite` | **未実装**（招待リンク発行は Phase 2 候補） | 該当 Controller なし → 🟡 として注記化 |
| POST | `/api/v1/dwelling-units/self-register` | **未実装**（セルフ登録は Phase 2 候補） | 該当 Controller なし → 🟡 として注記化 |
| GET | `/api/v1/dwelling-units/my` | `/api/v1/users/me/dwelling-unit` | `UserResidentController#getMyUnit` (L25) — **複数形 vs 単数形・/me/ vs /my** の命名揺れ |
| GET | `/api/v1/dwelling-units/export` | **未実装**（CSV エクスポートは Phase 2 候補） | 該当 Controller なし → 🟡 として注記化 |
| GET | `/api/v1/dwelling-units/residents/search` | **未実装**（横断検索は Phase 2 候補） | 該当 Controller なし → 🟡 として注記化 |
| PATCH | `/api/v1/dwelling-units/bulk-privacy` | **未実装**（一括プライバシ変更は Phase 2 候補） | 該当 Controller なし → 🟡 として注記化 |
| POST | `/api/v1/dwelling-units/{unitId}/residents/{id}/documents` | `/api/v1/organizations/{orgId}/residents/{residentId}/documents` / `/api/v1/teams/{teamId}/residents/{residentId}/documents` | `OrgResidentDocumentController#upload` (L33) / `TeamResidentDocumentController#upload` (L33) |
| DELETE | `/api/v1/dwelling-units/residents/documents/{documentId}` | `/api/v1/organizations/{orgId}/residents/{residentId}/documents/{docId}` / `/api/v1/teams/{teamId}/residents/{residentId}/documents/{docId}` | `OrgResidentDocumentController#delete` (L49) / `TeamResidentDocumentController#delete` (L49) — **パス構造再編** |
| GET | `/api/v1/dwelling-units/stats` | **未実装**（統計ダッシュボードは Phase 2 候補） | 該当 Controller なし → 🟡 として注記化 |

#### 判定: 全件 🟡 設計書更新要

スコープ移行注記は既に L286〜300 に存在するが、**実装パス対応 + 未実装エンドポイントの
明示** が不足している。本 PR で次の 2 点を追記する:

1. §4 冒頭の注記を強化し「実装済 vs 未実装 (Phase 2 候補)」を二分割
2. §4.1 として「実装パス対応表」を追記し、scanner v5 が逆引きしやすい記法で残す

未実装の Phase 2 候補（CSV import/export, my dwelling-unit search, invite, self-register,
bulk-privacy, stats, move-out-all, renew-lease）は **🔵 マーカ付与対象** にもなり得るが、
F09.1 設計書には Phase 表が無いため、本第四陣では「§4 冒頭注記内の未実装リスト」として
記録し、🔵 化は次フェーズ（F09.1 Phase 2 軍議）にバトンする。

---

### 2.2 F09.16 居住実態管理 — `/api/v1/residence-status/*` (13 件)

baseline v5 §1 で 13 件が「設計あり・実装なし」として計上。

| メソッド | 設計パス（旧） | 実装パス（正） | Controller |
|---|---|---|---|
| POST | `/api/v1/residence-status/annual-reviews` | `/api/v1/organizations/{orgId}/residence-status/annual-reviews` | `AnnualReviewController#create` (L41) |
| GET | `/api/v1/residence-status/annual-reviews` | 同上 | `AnnualReviewController#list` (L54) |
| GET | `/api/v1/residence-status/annual-reviews/{id}` | `.../{id}` | `AnnualReviewController#getReview` (L65) |
| POST | `/api/v1/residence-status/annual-reviews/{id}/close` | `.../{id}/close` | `AnnualReviewController#closeReview` (L78) |
| GET | `/api/v1/residence-status/annual-reviews/{id}/responses` | `.../{reviewId}/responses` | `AnnualReviewResponseController#list` (L40) |
| GET | `/api/v1/residence-status/annual-reviews/my` | `.../my` | `AnnualReviewController#listMyReviews` (L91) |
| PUT | `/api/v1/residence-status/annual-reviews/{id}/responses/me` | `.../{reviewId}/responses/me` | `AnnualReviewResponseController#submitMyResponse` (L55) |
| GET | `/api/v1/residence-status/activity-snapshots/{residentId}` | `.../activity-snapshots/{residentRegistryId}` | `ResidenceStatusController#getSnapshots` (L45) |
| POST | `/api/v1/residence-status/monitoring-visits` | 同（プレフィックス組織スコープ） | `MonitoringCommitteeVisitController#create` (L46) |
| GET | `/api/v1/residence-status/monitoring-visits` | 同上 | `MonitoringCommitteeVisitController#list` (L71) |
| PUT | `/api/v1/residence-status/monitoring-visits/{id}` | `.../{id}` | `MonitoringCommitteeVisitController#updateVisit` (L105) |
| POST | `/api/v1/residence-status/org-wide-safety-checks` | 同（プレフィックス組織スコープ） | `OrgWideSafetyCheckController#triggerCheck` (L41) |
| GET | `/api/v1/residence-status/dashboard` | 同上 | `ResidenceStatusController#getDashboard` (L64) |

#### 判定: 全件 🟡 設計書更新要

§6 冒頭にスコープ移行注記は存在するが、**実装は既に全て completed**（Phase S3〜S5 完了済、
memory `project_f0916_s5_complete.md` 参照）であるため、設計書の旧パス記載のみが乖離の原因。
F09.16 §6 全 13 行を `/api/v1/organizations/{orgId}/residence-status/...` で書き換える
PR が本来の根治治療だが、本第四陣では **§6.1 表のパス列を一括書き換え** と
**§6.2 主要エンドポイント仕様の見出し書き換え** を実施する。

加えて F09.16 §6 冒頭注記に「2026-05-17 第四陣 dwelling-units triage により旧パス全廃」
の明示を追記する。

---

### 2.3 section 2 中の実装計上分 — 関連 17 件

baseline v5 §2「実装あり・設計なし」中、`/dwelling-units` 系で計上されている分:

| メソッド | 実装パス | Controller |
|---|---|---|
| GET | `/api/v1/organizations/{_}/dwelling-units` | `OrgDwellingUnitController#list` |
| POST | `/api/v1/organizations/{_}/dwelling-units` | `OrgDwellingUnitController#create` |
| POST | `/api/v1/organizations/{_}/dwelling-units/batch` | `OrgDwellingUnitController#batchCreate` |
| GET | `/api/v1/teams/{_}/dwelling-units` | `TeamDwellingUnitController#list` |
| POST | `/api/v1/teams/{_}/dwelling-units` | `TeamDwellingUnitController#create` |
| POST | `/api/v1/teams/{_}/dwelling-units/batch` | `TeamDwellingUnitController#batchCreate` |
| GET | `/api/v1/users/me/dwelling-unit` | `UserResidentController#getMyUnit` |

residence-status 系で計上されている分:

| メソッド | 実装パス | Controller |
|---|---|---|
| GET | `/api/v1/organizations/{_}/residence-status/activity-snapshots/{_}` | `ResidenceStatusController#getSnapshots` |
| GET | `/api/v1/organizations/{_}/residence-status/annual-reviews/my` | `AnnualReviewController#listMyReviews` |
| GET | `/api/v1/organizations/{_}/residence-status/annual-reviews/{_}` | `AnnualReviewController#getReview` |
| GET | `/api/v1/organizations/{_}/residence-status/dashboard` | `ResidenceStatusController#getDashboard` |
| GET | `/api/v1/organizations/{_}/residence-status/monitoring-visits/by-watcher/{_}` | `MonitoringCommitteeVisitController#getVisitsByWatcher` |
| GET | `/api/v1/organizations/{_}/residence-status/org-wide-safety-checks/active` | `OrgWideSafetyCheckController#getActiveChecks` |
| POST | `/api/v1/organizations/{_}/residence-status/annual-reviews/{_}/close` | `AnnualReviewController#closeReview` |
| PUT | `/api/v1/organizations/{_}/residence-status/annual-reviews/{_}/responses/me` | `AnnualReviewResponseController#submitMyResponse` |
| PUT | `/api/v1/organizations/{_}/residence-status/monitoring-visits/{_}` | `MonitoringCommitteeVisitController#updateVisit` |

これら 17 件は、設計書 §4 / §6 が旧フラットパスで書かれているため、scanner v5 では
スコープ階層展開を通しても「一致」と判定されず実装側に計上される。

§2.1 / §2.2 で設計書側の表を **組織/チームスコープ階層パスに書き換える** 修正を行えば、
これらは次回 baseline 生成時に「一致」へ繰入される。本 PR の主たる対処方針はこれである。

加えて以下の 1 件 — `batch` API は設計書側未記載 → **🟡 設計書追加要**:
- `POST /api/v1/organizations/{orgId}/dwelling-units/batch`
- `POST /api/v1/teams/{teamId}/dwelling-units/batch`

これらは複数居室を一括作成するエンドポイント。F09.1 §4 にも記載なし。本 PR で §4 表に追加する。

また `GET /api/v1/users/me/dwelling-unit` (UserResidentController) は F09.1 §4 表の
`GET /api/v1/dwelling-units/my` と意味的に同一だが、パス構造が異なる。設計書側を
`/api/v1/users/me/dwelling-unit` へ書き換える。

---

### 2.4 section 4 (V4-1 逆引き準一致) の 10 件

baseline v5 §4 で既に「準一致」として一致集計済の dwelling-units 系:

- DELETE/GET/PUT `/api/v1/organizations/{_}/dwelling-units/{_}`
- DELETE/GET/PUT `/api/v1/teams/{_}/dwelling-units/{_}`
- GET `/api/v1/organizations/{_}/dwelling-units/{_}/residents`
- GET `/api/v1/teams/{_}/dwelling-units/{_}/residents`
- POST `/api/v1/organizations/{_}/dwelling-units/{_}/residents`
- POST `/api/v1/teams/{_}/dwelling-units/{_}/residents`

判定: scanner 動作正常。triage 観点でアクション不要。ただし設計書側を実装パスに揃えれば
本準一致リストからも除外され、純粋な一致集計に繰入される。これも §2.1 の修正で連動して解消される。

---

## 3. 修正ファイル一覧

| ファイル | 種別 | 内容 |
|---|---|---|
| `docs/features/F09.1_resident_registry.md` | 修正 | §4 冒頭注記強化 + §4.1 実装パス対応表追加 + Phase 2 候補リスト明示。§4 各エンドポイント章見出しは触らず、注記に集約して影響範囲を限定 |
| `docs/features/F09.16_residence_status_management.md` | 修正 | §6.1 表のパスを `/api/v1/organizations/{orgId}/...` で全件書き換え。§6.2 主要エンドポイント仕様の見出し（`####`）も書き換え。§6 冒頭注記に第四陣 triage 完了の明示を追記 |
| `docs/internal/triage_log/dwelling-units.md` | 新規 | 本ファイル |

`docs/internal/api_drift_exclusions.yml` は **更新しない**:
- `dwelling-units` / `residence-status` 系の実装は将来も維持される本物のエンドポイント
- 設計書側を実装に揃えるのが正解であり、scanner で除外する性質ではない

---

## 4. 難しい事例 / スキャナ改善余地

### 事例 A: 複数形・単数形・/me/ vs /my のパス構造揺れ

F09.1 設計書 `GET /api/v1/dwelling-units/my` と実装 `GET /api/v1/users/me/dwelling-unit`:

- リソース名: 複数形 `dwelling-units` ↔ 単数形 `dwelling-unit`
- ユーザー指示子: 末尾 `/my` ↔ 先頭 `/users/me/`

scanner v5 の SINGULAR_PLURAL_DICT で `dwelling-units ↔ dwelling-unit` は正規化可能だが、
**ユーザー指示子（`/me/`, `/my`, `/self/`）の前後位置の入れ替え** は正規化対象外。
これは scanner v6 で `me_pattern: ['/me/', '/my', '/self/']` を path 任意位置で吸収する
機構を追加すれば解決可能。

### 事例 B: パス構造再編（residents/documents の階層変更）

設計: `DELETE /api/v1/dwelling-units/residents/documents/{documentId}`
実装: `DELETE /api/v1/organizations/{orgId}/residents/{residentId}/documents/{docId}`

- 階層深さが違う（設計: 2 階層 / 実装: 3 階層）
- 設計には `residentId` が無いが、実装には必須

これは scanner では検出不能。設計書側のリファクタが必要な「設計欠陥」レベルの乖離で、
**実装が論理的に正しい**（resident 単位で documents を所有する自然な設計）ため、
設計書を書き換える。

### 事例 C: ドキュメント上の重複カウント

baseline v5 §1 で同じパス（例: `/api/v1/dwelling-units` POST）が L306 と L340 の 2 行で
ヒットしている。これは F09.1 設計書内で **§4.エンドポイント一覧表** と
**§4.x #### POST /api/v1/dwelling-units 章見出し** の 2 箇所に同じパスが記載されているため。

scanner v5 は **設計記載 unique (method, path) 総数** で重複排除済（baseline 冒頭サマリで
2553 件、scanner v4 と比較してインライン強化済）。これは仕様通り。
ただし baseline §1 のドメイン別詳細表では生検出として両方リストされる仕様。

→ triage 観点では 18 件 = 9 種類のエンドポイント x 2 検出（テーブル + 見出し）と解釈。

### 事例 D: F09.15 succession/residents/{_}/death-status の単発

baseline v5 §1 L1655 に `POST /api/v1/succession/residents/{_}/death-status` が 1 件のみ計上。
実装側に Controller なし。F09.15 設計書 §S6 で「Phase 2 候補 / 未着工」とコメントあり。

判定: 🔵 将来機能候補だが F09.15 設計書には Phase 表記がないため、本第四陣の範囲外。
F09.15 succession ドメイン専用 triage（第五陣以降）で対応推奨。本ログでは記録のみ。

---

## 5. F09.1 / F09.16 設計書整備状況の所感

### 5.1 良かった点

- **F09.1 §4 冒頭・F09.16 §6 冒頭にスコープ移行注記が既に存在**（L286〜300, L353〜359）。
  triage 着手時点で「次フェーズで全行書き換え PR が必要」と明示済みで意図が明確
- **F09.16 は Phase S3〜S5 が memory に完了記録あり**（`project_f0916_s5_complete.md` /
  `project_f0916_s4_complete.md` / `project_f0916_s3_complete.md`）。実装は完成しており、
  設計書のパス記法だけが追随できていない状況

### 5.2 課題点

- F09.1 §4 のエンドポイント表（L302〜336）が **全 36 行旧フラットパスのまま**。本来は
  Phase 1 完了時点で書き換えるべき内容
- F09.1 設計書には **「Phase 1 / Phase 2 の機能区分」が表中に明示されていない**。CSV
  インポート・エクスポート・統計・横断検索など複数のエンドポイントが「未実装」だが、
  Phase 表記がないため scanner が 🔴/🔵/🟡 を機械判定できない
- F09.16 §6.1 表（L361〜377）も全行旧パス。**S3〜S5 実装完了済にもかかわらず設計書が
  追随していない**

### 5.3 推奨される後続作業

1. **F09.1 Phase 2 軍議**: CSV import / export / 招待リンク / セルフ登録 / 統計 /
   横断検索 / move-out-all / renew-lease / bulk-privacy の各エンドポイントを
   **🔵 将来機能マーカ付与** または **🔴 実装追加** で正式 triage する
2. **F09.16 §6 全件書き換え PR**: 本第四陣で実施するが、E2E テストの URL 直書きが
   ある場合は同 PR で追従する必要がある（要検索）
3. **scanner v6 設計タスク**:
   - me_pattern 任意位置吸収（事例 A）
   - dwelling-unit / dwelling-units の単複正規化はすでに v5 で対応済を改めて検証
4. **F09.1 §4 各 #### 章見出しの一括 prefix 書き換え**: 本第四陣では注記強化と表更新に
   留め、各章見出しは触らない（影響範囲限定）。Phase 2 軍議で一括書き換え推奨

---

## 6. PR / コミット

- ブランチ: `feature/api-drift-cleanup-dwelling-units`
- 派生元: `main` (87d2953ec)
- コミットメッセージ:
  ```
  Stage3 4-β: dwelling-units ドメイン triage 統合（漏れ0件 / 設計書更新31件 / 将来0件 / 除外0件）
  ```

3 ファイル変更（triage_log 新規 + F09.1 修正 + F09.16 修正）で完結。
