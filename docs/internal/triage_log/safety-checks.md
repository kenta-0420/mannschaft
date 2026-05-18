# Stage3 safety-checks ドメイン triage 作業ログ

> ベースライン: `docs/internal/api_drift_baseline.md` v5（2026-05-17）
> 担当: 第四陣 4-ε 足軽
> 期間: 2026-05-17
> ブランチ: `feature/api-drift-cleanup-safety-checks`
> 主設計書: `docs/features/F03.6_safety_check.md`（🟢 設計完了 / Phase 3 / 2026-03-18 最終更新）
> 副設計書: `docs/features/F09.16_residence_status_management.md`（横展開安否確認 S3-C）

---

## 0. 取扱方針

F03.6 は 2026-03-18 に「Phase 3 で実装」と明記された **設計完了済みの大型設計書**（1400+ 行）が
存在し、`SafetyCheckController` / `SafetyTemplateController` / `SafetyFollowupController` /
`SafetyAdminController` の 4 本（ユーザー向け 3 本 + SYSTEM_ADMIN 向け 1 本）まで実装が進んだ。

ただし以下のように **命名/動詞揺れが集中的に発生**しており、Phase 3 後期で「設計書を更新せずに
実装側を REST 慣行に合わせてリファクタした」と見受けられる:

| 揺れ区分 | 設計書 | 実装（正） |
|---|---|---|
| close の動詞 | `PATCH /{id}/close` | `POST /{id}/close` |
| bulk respond の位置 | `POST /bulk-respond` （トップレベル） | `POST /{id}/respond/bulk` （セッション配下） |
| templates 更新動詞 | `PUT /templates/{id}` | `PATCH /templates/{id}` |
| templates 削除位置 | `DELETE /templates/{id}` （ユーザー側） | `DELETE /system-admin/safety-checks/templates/{id}` のみ |
| followups の位置 | `PUT /{id}/results/followups/{respId}` | `PATCH /followups/{followupId}` （フラット） |
| presets 命名 | `message-presets` | `presets` |

加えて F09.16 S3-C で実装された `OrgWideSafetyCheckController` は、設計書 §4 と異なり
**organization スコープパス展開を採用**（`/api/v1/organizations/{orgId}/residence-status/org-wide-safety-checks`）
し、`/active` という未設計エンドポイントを追加している。

本 triage では:

1. **命名/動詞/位置揺れ**は 🟡 設計書更新要（実装側が REST 慣行に沿っており正）
2. **`pending` / `my` 未実装**は 🔵 将来機能タグ付与（フロント実装 Phase 4 待ち）
3. **F09.16 横展開安否確認の URL 構造差**は 🟡 F09.16 §4 更新
4. **scanner v5 の (method, path) 重複行**は 🐞 偽陽性として記録（dedupは scanner v6 課題）
5. **新規 exclusions.yml 追加は不要**（system-admin は既登録済み）

---

## 1. 集計

### 1.1 ドメイン全体集計

| 分類 | 件数 | 備考 |
|---|---:|---|
| 🔴 真の漏れ（実装追加） | 0 | — |
| 🟡 設計書更新要 | 14 | 命名/動詞/位置揺れ + F09.16 scope パス展開 |
| 🔵 将来機能（🔵 タグ付与） | 2 | `GET /pending`, `GET /my`（フロント実装待ち） |
| ⚪ 除外（exclusions.yml） | 0 | system-admin は既登録 |
| 🐞 スキャナ偽陽性（重複行） | 14 | baseline で同一エンドポイントが §4 一覧 + 個別仕様セクション両方で検出 |
| **合計** | **30** | baseline `/api/v1/safety-checks/*` 26 行（設計+実装+一致 一意化前）+ F09.16 関連 4 行 |

### 1.2 baseline `/api/v1/safety-checks/*` の dedup 後 endpoint 単位の内訳

baseline は同一 (method, path) を §4 一覧表 + 個別仕様セクションの双方から拾うため、12 件の
うち実エンドポイントは **9 件**（重複 3 件除く）。

| 区分 | 行数 (baseline) | dedup後 件数 |
|---|---:|---:|
| 設計あり・実装なし | 26 | 9（実エンドポイント） |
| 実装あり・設計なし | 8 | 8 |
| 一致 | 4 | 4 |

#### 設計あり・実装なし（dedup後 9 件）の内訳

| # | エンドポイント | 区分 |
|---|---|---|
| A-1 | `PATCH /api/v1/safety-checks/{_}/close` | 🟡 動詞揺れ（実装は POST） |
| A-2 | `POST /api/v1/safety-checks/bulk-respond` | 🟡 位置揺れ（実装は `{id}/respond/bulk`） |
| A-3 | `GET /api/v1/safety-checks/message-presets` | 🟡 命名揺れ（実装は `/presets`） |
| A-4 | `GET /api/v1/safety-checks/templates` | 🟡 ★一致候補だが scanner v5 が `/{_}` 個別と混同して漏れ判定 |
| A-5 | `POST /api/v1/safety-checks/templates` | 🟡 同上 |
| A-6 | `PUT /api/v1/safety-checks/templates/{_}` | 🟡 動詞揺れ（実装は PATCH） |
| A-7 | `DELETE /api/v1/safety-checks/templates/{_}` | 🟡 位置揺れ（実装は SYSTEM_ADMIN 側のみ） |
| A-8 | `GET /api/v1/safety-checks/pending` | 🔵 未実装（Phase 4） |
| A-9 | `GET /api/v1/safety-checks/my` | 🔵 未実装（Phase 4） |
| A-10 | `POST /api/v1/safety-checks` | 🐞 偽陽性（実装 L51 に存在、scanner が一覧表+個別表で 2 重カウント） |
| A-11 | `GET /api/v1/safety-checks` | 🐞 偽陽性（実装 L63 に存在） |
| A-12 | `PUT /api/v1/safety-checks/{_}/results/followups/{_}` | 🟡 位置/動詞揺れ（実装は PATCH `/followups/{id}`） |

#### 実装あり・設計なし（8 件）の内訳

| # | エンドポイント | 設計書での対応関係 |
|---|---|---|
| B-1 | `GET /api/v1/safety-checks/history` | 🟡 設計書未記載（実装は CLOSED 履歴用途。`GET /` の status=CLOSED 相当だが別 API） |
| B-2 | `GET /api/v1/safety-checks/presets` | 🟡 命名揺れ（設計書 `message-presets`） |
| B-3 | `GET /api/v1/safety-checks/templates/{_}` | 🟡 設計書未記載（一覧のみ記載、詳細 API 追加要） |
| B-4 | `GET /api/v1/safety-checks/{_}/unresponded` | 🟡 設計書未記載（実装は未回答ユーザー一覧 API、設計書 §4 `results` の補助 API） |
| B-5 | `PATCH /api/v1/safety-checks/followups/{_}` | 🟡 位置/動詞揺れ（設計書 PUT `/{id}/results/followups/{respId}`） |
| B-6 | `PATCH /api/v1/safety-checks/templates/{_}` | 🟡 動詞揺れ（設計書 PUT） |
| B-7 | `POST /api/v1/safety-checks/{_}/close` | 🟡 動詞揺れ（設計書 PATCH） |
| B-8 | `POST /api/v1/safety-checks/{_}/respond/bulk` | 🟡 位置揺れ（設計書 `bulk-respond`） |

### 1.3 F09.16 横展開安否確認関連（4 行）

| # | baseline 行 | 区分 |
|---|---|---|
| C-1 | `POST /api/v1/residence-status/org-wide-safety-checks`（設計あり・実装なし） | 🟡 F09.16 設計書を scope パス展開に書き換え |
| C-2 | `GET /api/v1/organizations/{_}/residence-status/org-wide-safety-checks/active`（実装あり・設計なし） | 🟡 F09.16 設計書に追加 |

---

## 2. 🟡 設計書更新要 詳細（14 件）

### 2.1 F03.6 §4 エンドポイント一覧表（L257-282）の書き換え

| 行 | 修正前 | 修正後 | 理由 |
|---:|---|---|---|
| 262 | `PATCH /api/v1/safety-checks/{id}/close` | `POST /api/v1/safety-checks/{id}/close` | 実装は POST。状態遷移系の動詞は POST 慣行に合わせる |
| 264 | `POST /api/v1/safety-checks/bulk-respond` | `POST /api/v1/safety-checks/{id}/respond/bulk` | 実装は安否確認セッション ID をパスに含む形。複数 ID 一括は別 API で検討 |
| 268 | `GET /api/v1/safety-checks/message-presets` | `GET /api/v1/safety-checks/presets` | 実装に合わせて `presets` 命名で統一 |
| 272 | `PUT /api/v1/safety-checks/templates/{id}` | `PATCH /api/v1/safety-checks/templates/{id}` | 実装は PATCH。部分更新セマンティクスに合致 |
| 273 | `DELETE /api/v1/safety-checks/templates/{id}` | （削除して system-admin 側に統合） | 実装ではテンプレート削除は SYSTEM_ADMIN 専用とした。一般 ADMIN は作成・更新のみ |
| 274 | `PUT /api/v1/safety-checks/{id}/results/followups/{responseId}` | `PATCH /api/v1/safety-checks/followups/{followupId}` | 実装は `safety_response_followups.id` をパスキーとするフラット URL。動詞も PATCH |
| 275 | `GET /api/v1/system-admin/safety-checks/message-presets` | `GET /api/v1/system-admin/safety-checks/presets` | 命名統一 |
| 276 | `POST /api/v1/system-admin/safety-checks/message-presets` | `POST /api/v1/system-admin/safety-checks/presets` | 同上 |
| 277 | `PUT /api/v1/system-admin/safety-checks/message-presets/{id}` | `PATCH /api/v1/system-admin/safety-checks/presets/{id}` | 命名統一 + 動詞統一 |
| 278 | `PATCH /api/v1/system-admin/safety-checks/message-presets/{id}/active` | （削除して PATCH `/presets/{id}` に統合） | 実装では `active` フィールドも `UpdatePresetRequest` で更新可能 |
| 281 | `PUT /api/v1/system-admin/safety-checks/templates/{id}` | `PATCH /api/v1/system-admin/safety-checks/templates/{id}` | 動詞統一 |

### 2.2 新規追記（実装あり・設計なし 4 件）

§4 一覧表に以下 4 行を追加:

| メソッド | パス | 認証 | 説明 |
|---|---|---|---|
| GET | `/api/v1/safety-checks/{id}/unresponded` | ADMIN | 未回答ユーザー一覧（CSV エクスポートやリマインド対象選定に使用） |
| GET | `/api/v1/safety-checks/history` | ADMIN | クローズ済み安否確認の履歴一覧（scope_type/scope_id 必須、ページング対応） |
| GET | `/api/v1/safety-checks/templates/{id}` | ADMIN | テンプレート詳細取得 |
| POST | `/api/v1/safety-checks/{id}/respond/bulk` | ADMIN | 他メンバー代理回答（管理者用、bulk-respond と置き換え） |

### 2.3 個別仕様セクション（L449, L541, L876, L892, L906）の書き換え

L449 の `#### PATCH /api/v1/safety-checks/{id}/close` を `#### POST` に。
L541 の `#### POST /api/v1/safety-checks/bulk-respond` を `#### POST /api/v1/safety-checks/{id}/respond/bulk` に書き換え + パスパラメータ・リクエストボディの説明調整。
L876 の `#### PUT /api/v1/safety-checks/templates/{id}` を `#### PATCH` に。
L892 の `#### DELETE /api/v1/safety-checks/templates/{id}` を削除（SYSTEM_ADMIN 側に統合済み）。
L906 の `#### PUT /api/v1/safety-checks/{id}/results/followups/{responseId}` を `#### PATCH /api/v1/safety-checks/followups/{followupId}` に書き換え + パス構造の説明調整。

### 2.4 F09.16 §4（L376, L399）の書き換え

L376 を実装に合わせて scope パス展開へ書き換え + GET /active を追加:

```diff
-| POST | `/api/v1/residence-status/org-wide-safety-checks` | ADMIN | 管理組合横展開安否確認の発動 |
+| POST | `/api/v1/organizations/{orgId}/residence-status/org-wide-safety-checks` | ADMIN | 管理組合横展開安否確認の発動 |
+| GET | `/api/v1/organizations/{orgId}/residence-status/org-wide-safety-checks/active` | ADMIN | 未クローズの横展開安否確認一覧 |
```

L399 の `#### POST /api/v1/residence-status/org-wide-safety-checks` を
`#### POST /api/v1/organizations/{orgId}/residence-status/org-wide-safety-checks` に書き換え。

---

## 3. 🔵 将来機能（Phase 4 未着工分）2 件

設計書側に記載があるが実装が無い機能。F03.6 §4 の該当行に **🔵 タグ**を追記する。

| # | エンドポイント | 設計行 | 役割 |
|---|---|---:|---|
| D-1 | `GET /api/v1/safety-checks/pending` | L266 | ログイン直後にブロッキングモーダル表示用の未回答リスト。フロントエンド統合 Phase 4 で実装 |
| D-2 | `GET /api/v1/safety-checks/my` | L267 | 自分の回答履歴一覧。マイページ統合 Phase 4 で実装 |

設計書 §4 該当行末尾に `🔵 Phase 4 未着工` を追記。

---

## 4. 🐞 スキャナ偽陽性（重複行）14 件

baseline で同一 (method, path) が複数行記載されている例（設計書 §4 一覧表 + §4 個別仕様セクション
の両方を独立カウント）:

| エンドポイント | baseline 行 | 設計書行 |
|---|---|---|
| DELETE `/safety-checks/templates/{_}` | 1437 + 1438 | L273 + L892 |
| GET `/safety-checks` | 1439 + 1440 | L260 + L347 |
| GET `/safety-checks/message-presets` | 1441 + 1442 + 1443 | L268 + L762 + F04.3 L774 |
| GET `/safety-checks/my` | 1444 + 1445 | L267 + L717 |
| GET `/safety-checks/pending` | 1446 + 1447 + 1448 | L266 + L681 + F04.3 L702 |
| GET `/safety-checks/templates` | 1449 + 1450 | L270 + L809 |
| PATCH `/safety-checks/{_}/close` | 1451 + 1452 | L262 + L449 |
| POST `/safety-checks` | 1453 + 1454 | L259 + L286 |
| POST `/safety-checks/bulk-respond` | 1455 + 1456 | L264 + L541 |
| POST `/safety-checks/templates` | 1457 + 1458 | L271 + L847 |
| PUT `/safety-checks/templates/{_}` | 1459 + 1460 | L272 + L876 |
| PUT `/safety-checks/{_}/results/followups/{_}` | 1461 + 1462 | L274 + L906 |

scanner v5 が **§4 一覧表の行と §4 個別仕様セクションの見出し行の両方を独立カウント**する仕様
（incidents triage でも同様）。scanner v6 で「設計書内の同一 (method, path) 重複は 1 件に dedup」処理
追加候補。本 triage では偽陽性記録のみ。

---

## 5. ⚪ 除外（exclusions.yml）

新規追加は **不要**。`/api/v1/system-admin/**` は既に登録済み（L68-70）で、SYSTEM_ADMIN 経路の
プリセット/テンプレート CRUD は除外側に倒れる。`/api/v1/safety-checks/**` 本体は設計書化対象で
あり、除外しない。

---

## 6. 修正ファイル一覧

| ファイル | 種別 | 内容 |
|---|---|---|
| `docs/features/F03.6_safety_check.md` | 編集 | §4 一覧表 11 行修正 + 4 行追加 + 個別仕様 5 セクション書き換え + 🔵 タグ 2 行追記 |
| `docs/features/F09.16_residence_status_management.md` | 編集 | §4 横展開安否確認 1 行書き換え + 1 行追加 + 個別仕様セクションの URL 書き換え |
| `docs/internal/triage_log/safety-checks.md` | 新規 | 本ファイル |

---

## 7. 判断に迷った点

### 7.1 `bulk-respond` トップレベル vs `{id}/respond/bulk` セッション配下

設計書 `POST /safety-checks/bulk-respond` は **「複数の safety_check_id への一括回答」** を想定
し、リクエストボディに `safety_check_ids: [1,2,3]` の配列を持つ（F03.6 §4 L548-555）。
実装 `POST /safety-checks/{id}/respond/bulk` は **「1 つの安否確認に対する代理一括回答」**（管理者が
複数ユーザー分を一括投入）を想定する別機能。

つまり名前は似ているが **機能が異なる**。設計書側の「複数 ID 一括回答」は **🔵 Phase 4 未着工**
扱いが本来正しいが、現実装の `/respond/bulk` で機能カバー範囲が広い（代理投入も同等のユースケースに
使える）ため、本 triage では:
- 設計書 §4 L264 を `POST /api/v1/safety-checks/{id}/respond/bulk` に置き換え
- 設計書 §4 L541 個別仕様セクションを実装意味（管理者代理一括回答）へ書き換え
- 「複数 ID 一括回答」を新規 🔵 行として追加する案は採用せず、ユーザー側の bulk-respond は
  フロント側で連続 POST に分解する運用とする

将来 F03.6 軍議で再検討する場合は、本判断を再考の余地ありとして注記する。

### 7.2 followups の URL 構造（PUT `/{id}/results/followups/{respId}` vs PATCH `/followups/{id}`）

設計書は安否確認セッション ID × 回答 ID をパスに含めるネスト構造、実装は followup ID 単独の
フラット構造。フラットの方が **CASCADE 削除や複数 followup の検索が容易** であり、F03.6 のように
1 followup = 1 つの要支援回答に紐付く設計では実装側の方が自然。設計書側を書き換える。

### 7.3 DELETE templates をユーザー側から外して SYSTEM_ADMIN のみとする判断

設計書は `DELETE /api/v1/safety-checks/templates/{id}`（ADMIN 用）を持つが、実装の
`SafetyTemplateController` には DELETE が **意図的に存在しない**。テンプレート削除は
`SafetyAdminController` の SYSTEM_ADMIN 経路のみに集約されている。

これは「組織管理者は誤ってテンプレートを削除しないように、削除権限を SYSTEM_ADMIN まで上げる」
という安全設計と読み取れる。本 triage では実装側の判断を尊重し、設計書 §4 L273（DELETE templates
の ADMIN 側）を削除する。

### 7.4 `message-presets` vs `presets` 命名

設計書は `message-presets`（より説明的）、実装は `presets`（簡潔）。本 triage では実装側を正と
して設計書全体を `presets` に揃える。`message-presets` は他ドメインで類似 API（例: お知らせ
プリセット）が出てきた際の混乱要因になるため、より一般的な `presets` の方が拡張時に問題が
起きにくい。

### 7.5 F09.16 横展開安否確認の URL 構造（フラット vs scope パス展開）

F09.16 §4 はフラット URL `/api/v1/residence-status/org-wide-safety-checks`、実装は scope パス
展開 `/api/v1/organizations/{orgId}/residence-status/org-wide-safety-checks`。F09.16 全体が他
ドメインと同様に組織スコープを URL パスで明示する方針に寄っており（dashboard 等も同様）、
実装の scope パス展開が正。設計書側を書き換える。

---

## 8. PR / コミット

- ブランチ: `feature/api-drift-cleanup-safety-checks`
- 派生元: `main` (87d2953ec)
- コミットメッセージ:

```
Stage3 4-ε safety-checks: triage 30件 → 漏れ0/更新14/将来2/除外0/偽陽性14

- F03.6 §4 一覧表 11 行修正（命名/動詞/位置揺れの根治）
- F03.6 §4 一覧表 4 行追加（unresponded/history/templates GET 詳細/respond bulk）
- F03.6 §4 個別仕様 5 セクション書き換え（close PATCH→POST、bulk-respond 位置、templates PUT→PATCH、followups URL構造、DELETE templates 削除）
- F03.6 🔵 Phase 4 未着工 2 行（pending, my）に🔵タグ付与
- F09.16 §4 横展開安否確認 URL を scope パス展開へ書き換え + /active 追加
```
