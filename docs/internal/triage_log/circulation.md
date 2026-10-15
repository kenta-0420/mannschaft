# /api/v1/circulation/* + /api/v1/circulations/* + /api/v1/circulation-documents/* triage 作業ログ（Stage 3 第四陣 4-α）

> 担当: 足軽（feature/api-drift-cleanup-circulation）
> 作業日: 2026-05-17
> 入力: `docs/internal/api_drift_baseline.md` v5 中の circulation ドメイン
>   - section 1 (設計あり・実装なし) `/api/v1/circulation/*` = **21 件**（重複行込みは 42 行、unique では 21 件）
>   - section 1 (設計あり・実装なし) `/api/v1/circulation-documents/*` = **1 件**
>   - section 2 (実装あり・設計なし) `/api/v1/circulations/*` = **4 件**（独自に表示）
>   - 加えて section 2 の他ブロック内に分散する circulation 系実装パス（V5-2 / V5-3 で正規化済もしくは未正規化）:
>     - `POST /api/v1/organizations/{_}/circulations/{_}/activate` (baseline line 2661)
>     - `GET /api/v1/teams/{_}/circulations/stats` (line 2834)
>     - `PATCH /api/v1/teams/{_}/circulations/{_}` (line 2897)
>     - `POST /api/v1/teams/{_}/circulations/{_}/activate` (line 2921)
>     - `GET /api/v1/me/circulations/created` (line 3106)
>   - V5-2 テナントスコープ正規化準一致（baseline line 3612, 3620, 3627, 3637）の 4 行は **集計対象外**（既に triage 済み扱い）
>   - 合計 **26 件**（section1 unique 21 + section1 documents 1 + section2 4 を主集計、加えて section2 分散の 5 件は派生整流対象として扱う）

---

## サマリ

| 分類 | 件数 | 備考 |
|---|---:|---|
| 🔴 真の漏れ（実装追加要） | 12 | `POST /circulation/{id}/force-complete` / `POST /circulation/batch/force-complete` / `POST /circulation/{id}/stamp/correct` / `POST /circulation/{id}/stamp/delegate` / `POST /circulation/{id}/remind` / `POST /circulation/{id}/duplicate` / `GET /circulation/{id}/export` / `GET /circulation/{id}/export/status` / `GET /circulation/{id}/status` / `PATCH /circulation/{id}/recipients/{userId}/skip` (ADMIN強制スキップ) / `DELETE /circulation/{id}/attachments/{attachmentId}` / `DELETE /circulation/{id}/comments/{commentId}` (Service 層 `deleteComment` 実装済 / Controller 欠落、ChatBookmarkController と同パターン) |
| 🟡 設計書更新要 | 13 | F05.2 §4 全体が「フラット URL」前提で書かれているが実装はスコープ分離（`/teams/{}/circulations` `/organizations/{}/circulations` `/me/circulations` + 子リソースは `/circulations/{}/...`）。**設計書を実装の URL 体系に揃える大規模整流**。さらに PUT→PATCH (1件)、start→activate (用語変更 1件)、my→created (1件)、F04.10 line 379 の `/circulation-documents` 表記を `/teams/{}/circulations` または `/organizations/{}/circulations` に差し替え (1件)、実装あり設計書未記載の 2 件 (`POST /circulations/{}/attachments/upload-url` / `DELETE /circulations/{}/recipients/{}` / `POST /circulations/{}/stamp/reject` / `POST /circulations/{}/stamp/skip` / `GET /teams/{}/circulations/stats`) を新規追記 |
| 🔵 将来機能（🔵 マーカ付与） | 0 | F05.2 は設計書本体が「全機能網羅」スタンスで書かれており「Phase X 未着工」マーカ無し。F09.14 連携の `circulation_document_id` 紐付けは別ドメイン（disclosure）の管轄で、本 triage 対象外 |
| ⚪ 除外（exclusions.yml） | 0 | 内部用 / 旧 prefix なし。circulation ドメインに admin/internal 用 API は無く、`/api/v1/circulation*` のみで構成される |
| 🐞 スキャナ偽陽性（重複行） | 1 | F05.2_circular.md の **§4 一覧表 (line 213-236) と §4.x 詳細セクション（line 305-1190 周辺）** で同一 (method, path) が 2 回登場し、scanner v5 重複排除ロジックを通り抜けたケース。21 行 × 2 = 42 行が表示されているが unique では 21 件。section 1 サマリ表で「21 件」と表示されていることから、scanner はある程度 unique 化しているように見えるが、F04.2 chat 同様、`§4 表ヘッダ` 行と `§4.x 詳細ヘッダ` 行が二重に拾われているため scanner v6 候補と同種の課題（chat triage 4 と同じ）|
| **合計** | **26** | section1 unique=22 + section2=4 |

> 補足: circulation ドメインは F05.2 (本体) と F09.14 (不動産情報開示の電子印鑑承認連携) と F04.10 (組織委員会のスコープ拡張) の 3 設計書から参照されており、
> 実装側は `circulation/controller/` 配下 7 ファイル（Team/Org/My/Comment/Stamp/Recipient/Attachment）+ Service 3 層で v1 級は形になっているが、
> **設計書が想定する高度機能（複製・代理押印・訂正・PDF エクスポート・強制完了・状況可視化・手動リマインド）が軒並み未実装**。
> triage の主目的は **F05.2 §4 一覧の URL 体系を実装側の「テナントスコープ分離」モデルに大規模整流すること、および 12 件の真の漏れ（Phase 2 残）を明文化すること**。

> 注意（チャネル/モード差）: F05.2 §3 のドメインモデル（SEQUENTIAL / SIMULTANEOUS / HYBRID 等の `circulation_mode`）と
> 実装側 `CirculationDocumentEntity` の `mode` 列は両者で揃っていることを確認済み（API 設計は本 triage の対象外）。

---

## 1. section 1（設計あり・実装なし）の分類

### A. `/api/v1/circulation/*` 21 件（重複行込みは 42 行）

#### A-1. 重複行起因（🐞 偽陽性のように見えるが scanner は集計時 unique 化）

scanner v5 が同一 (method, path) を **行数では 2 回** 計上したケース（baseline line 489-530 の 42 行）。
ただし baseline サマリ表 (line 48) では「21 件」と unique 化された値が出ているため、純粋偽陽性として
新規にカウントするのではなく、F05.2_circular.md の `§4 一覧表 (line 213-236)` と `§4.x 詳細セクション
(line 305-1190 周辺)` で同じエンドポイントが 2 回登場する **設計書側の構造起因** として、🐞 1 件で
記録するに留める。

判定: scanner v6 で「同一設計書内の同一 (method, path) は 1 件カウント」ロジック厳密化を推奨（chat triage 4 と同種）。

#### A-2. URL 体系の根本不整合（🟡 設計書更新要、最大の論点）

F05.2 §4 line 213-236 の 21 行はすべて **`/api/v1/circulation/...` （単数形・スコープ無し）** で記述されているが、
実装側は次のスコープ分離モデルで構築されている:

| 役割 | 実装プレフィックス | Controller |
|---|---|---|
| 組織スコープ CRUD | `/api/v1/organizations/{orgId}/circulations` | `OrgCirculationDocumentController` |
| チームスコープ CRUD | `/api/v1/teams/{teamId}/circulations` | `TeamCirculationDocumentController` |
| 自分宛一覧 | `/api/v1/me/circulations/created` | `MyCirculationController` |
| 子リソース（押印・コメント・添付・受信者） | `/api/v1/circulations/{documentId}/...` | `CirculationStampController` / `CirculationCommentController` / `CirculationAttachmentController` / `CirculationRecipientController` |

判定: 実装側のテナントスコープ分離は CLAUDE.md ドメイン境界原則（`organization_id` / `team_id` をシャードキーとする将来計画）に
照らして **正しい設計**。設計書 F05.2 §4 を実装の URL 体系に揃える整流が必要 🟡。

具体的な対応マッピング（実装あり、設計書 path 古い）:

| 設計書 path (古) | 実装 path (正) | 判定 |
|---|---|---|
| `GET /circulation` | `GET /teams/{teamId}/circulations` + `GET /organizations/{orgId}/circulations` | 🟡 |
| `POST /circulation` | `POST /teams/{teamId}/circulations` + `POST /organizations/{orgId}/circulations` | 🟡 |
| `GET /circulation/{id}` | `GET /teams/{teamId}/circulations/{documentId}` + `GET /organizations/{orgId}/circulations/{documentId}` | 🟡 |
| `PUT /circulation/{id}` | `PATCH /teams/{teamId}/circulations/{documentId}` | 🟡（PUT→PATCH 用語含む。Org は未実装 → 🔴 B-3 で別記） |
| `DELETE /circulation/{id}` | `DELETE /teams/{teamId}/circulations/{documentId}` | 🟡（Org は未実装 → 🔴 B-3） |
| `POST /circulation/{id}/start` | `POST /teams/{teamId}/circulations/{documentId}/activate` + `POST /organizations/{orgId}/circulations/{documentId}/activate` | 🟡（start→activate） |
| `POST /circulation/{id}/cancel` | `POST /teams/{teamId}/circulations/{documentId}/cancel` | 🟡（Org は未実装 → 🔴 B-3） |
| `POST /circulation/{id}/stamp` | `POST /circulations/{documentId}/stamp` | 🟡 |
| `GET /circulation/my` | `GET /me/circulations/created` | 🟡（my→created、ただし「created＝自分が作成した」と「自分宛＝to me」は概念が異なる → 後述 B-5 で精査） |
| `POST /circulation/{id}/attachments` | `POST /circulations/{documentId}/attachments` (実装あり) | 🟡 |
| `GET /circulation/{id}/comments` | `GET /circulations/{documentId}/comments` | 🟡 |
| `POST /circulation/{id}/comments` | `POST /circulations/{documentId}/comments` | 🟡 |

未実装の設計書 path（実装側にも対応 path なし、🔴 確定）:

| 設計書 path | 実装側調査結果 | 判定 |
|---|---|---|
| `POST /circulation/{id}/force-complete` | Service / Controller とも無し | 🔴 C-1 |
| `POST /circulation/batch/force-complete` | Service / Controller とも無し | 🔴 C-2 |
| `POST /circulation/{id}/stamp/correct` | Service / Controller とも無し | 🔴 C-3 |
| `POST /circulation/{id}/stamp/delegate` | Service / Controller とも無し | 🔴 C-4 |
| `POST /circulation/{id}/remind` | Service / Controller とも無し（ただし `reminderEnabled` / `reminderIntervalHours` フィールドは Entity に存在し、Phase 2 計画の片鱗あり） | 🔴 C-5 |
| `POST /circulation/{id}/duplicate` | Service / Controller とも無し | 🔴 C-6 |
| `GET /circulation/{id}/export` | Service / Controller とも無し | 🔴 C-7 |
| `GET /circulation/{id}/export/status` | Service / Controller とも無し | 🔴 C-8 |
| `GET /circulation/{id}/status` | Service / Controller とも無し（押印状況一覧。`getStats` (stats=集計) はあるが status 詳細一覧は別物） | 🔴 C-9 |
| `PATCH /circulation/{id}/recipients/{userId}/skip` | `DELETE /circulations/{documentId}/recipients/{recipientId}` が `CirculationRecipientController#removeRecipient` で実装あるが、**「他人を ADMIN 権限で強制スキップする」セマンティクスは違う**（受信者削除 vs スキップは別概念）| 🔴 C-10 |
| `DELETE /circulation/{id}/attachments/{attachmentId}` | `CirculationAttachmentController` に `@DeleteMapping` 無し（GET / POST `/upload-url` / POST `/attachments` のみ） | 🔴 C-11 |
| `DELETE /circulation/{id}/comments/{commentId}` | `CirculationCommentController` に `@DeleteMapping` 無し。**Service 層 `CirculationCommentService#deleteComment` (line 106) は実装済**（ChatBookmarkController と同パターン）| 🔴 C-12 |

---

### B. `/api/v1/circulation-documents/*` 1 件

| メソッド | パス | 設計書行 | 判定 |
|---|---|---:|---|
| POST | `/api/v1/circulation-documents` | F04.10 line 379 | 🟡 B-1（F04.10 「既存 API のスコープ拡張」表で `/circulation-documents` と古い path 名で表記されている。実装は `/teams/{}/circulations` / `/organizations/{}/circulations` に分離済。F04.10 設計書を `/circulations` (複数形・スコープ別) に書き換え） |

#### B-1. F04.10 line 379 の path 表記更新

実装: F04.10 §4「既存 API のスコープ拡張」表（line 374-381）で「`POST /api/v1/circulation-documents` に `scope_type: 'COMMITTEE'` 受容を追加」と書かれているが、実装にはそのエンドポイントは存在しない。

判定: F04.10 line 379 を `POST /api/v1/organizations/{orgId}/circulations`（および COMMITTEE スコープ拡張時の路線）に書き換え 🟡。設計書としては「COMMITTEE スコープを受容する作成 API」の主旨は変えず、path 表記のみ実装に合わせる。

---

### C. 真の漏れ（🔴 実装追加要、12 件）

#### C-1. `POST /api/v1/circulation/{id}/force-complete` 単体強制完了 未実装

設計書（F05.2 line 220, 737）: ADMIN が「未確認者が残っていても回覧を完了済みにする」操作。
実装側調査: `CirculationService` に `forceComplete` 系メソッド無し。Controller も無し。
判定: **🔴 真の漏れ**。優先度: 中（運用上 ADMIN が回覧を畳む手段が無いと滞留する。Phase 2 残）。

#### C-2. `POST /api/v1/circulation/batch/force-complete` 一括強制完了 未実装

設計書（F05.2 line 236, 1190）: 最大 20 件まで一括で強制完了。
実装側調査: なし。
判定: **🔴 真の漏れ**。優先度: 低（C-1 が先。一括版は管理画面ができてから）。

#### C-3. `POST /api/v1/circulation/{id}/stamp/correct` 訂正押印 未実装

設計書（F05.2 line 222, 649）: 逆さまハンコの訂正（二重線＋押し直し）。`seal_stamp_logs.correction_stamp_log_id` カラム（F05.2 §3 line 203）まで設計済。
実装側調査: なし。Entity `circulation_recipients.correction_stamp_log_id` を実装で参照しているコードを確認できれば DB 側準備済の可能性。
判定: **🔴 真の漏れ**。優先度: 中〜高（実物の業務で「押し間違えました」を救済する導線は必須）。

#### C-4. `POST /api/v1/circulation/{id}/stamp/delegate` 代理押印 未実装

設計書（F05.2 line 235, 1148）: ADMIN が不在者に代わって押印（代理印）。
実装側調査: なし。
判定: **🔴 真の漏れ**。優先度: 中（管理者の救済導線）。

#### C-5. `POST /api/v1/circulation/{id}/remind` 手動リマインド送信 未実装

設計書（F05.2 line 224, 771）: ロック解除済グループの未確認者に手動でリマインド通知を送信。
実装側調査: Service なし。Entity 側に `reminderEnabled` / `reminderIntervalHours` (line 151-152) があるため **自動リマインドの Scheduler は計画あり** だが、手動トリガ API は未実装。
判定: **🔴 真の漏れ**。優先度: 中。

#### C-6. `POST /api/v1/circulation/{id}/duplicate` 回覧の複製 未実装

設計書（F05.2 line 234, 1125）: 既存回覧から新規 DRAFT を作成（テンプレ的に複製）。
実装側調査: なし。
判定: **🔴 真の漏れ**。優先度: 低〜中（運用効率化施策、無くても初期運用は回る）。

#### C-7. `GET /api/v1/circulation/{id}/export` PDF エクスポート 未実装

設計書（F05.2 line 226, 876）: 押印済み証跡付き PDF エクスポート（COMPLETED のみ）。生成済 → 302、未生成 → 202。
実装側調査: なし。
判定: **🔴 真の漏れ**。優先度: 中（業務上「紙で残す」需要は強い）。

#### C-8. `GET /api/v1/circulation/{id}/export/status` PDF 生成状況 未実装

設計書（F05.2 line 227, 916）: C-7 の非同期生成ジョブの状態確認 API。
実装側調査: なし。
判定: **🔴 真の漏れ**。C-7 と同時実装になる。

#### C-9. `GET /api/v1/circulation/{id}/status` 押印状況一覧 未実装

設計書（F05.2 line 225, 803）: 受信者ごとの押印状況（STAMPED / PENDING / SKIPPED / REJECTED）一覧。詳細画面の主要表示要素。
実装側調査: なし。`CirculationService.getStats` (line 465) は **集計（件数）** であり、「誰が押した・誰が未済」を一覧返却するメソッドではない。
判定: **🔴 真の漏れ**。優先度: 高（ある意味メイン画面の主要 API。これが無いと UI が組めない）。

> 注: 暫定的に `GET /circulations/{documentId}` 詳細レスポンスに `recipients[]` 配列を含めて代用している可能性あり。フロント側の実呼び出しを別 PR で突合して判断必要。

#### C-10. `PATCH /api/v1/circulation/{id}/recipients/{userId}/skip` ADMIN 強制スキップ 未実装

設計書（F05.2 line 223, 705）: ADMIN が「離職した・もう確認不要」と判断した受信者を強制的にスキップ状態にする。
実装側調査:
- `CirculationRecipientController#removeRecipient` (`DELETE /circulations/{documentId}/recipients/{recipientId}`, line 66) は **受信者削除** であり、状態を SKIPPED にするのとは別概念（DELETE は履歴から消す、PATCH/skip は監査ログを残しつつスキップ印を立てる）
- `CirculationStampController#skip` (`POST /circulations/{documentId}/stamp/skip`) は **自分自身がスキップする** API（受信者本人が「該当しないので飛ばす」と申告）。`/recipients/{userId}/skip` は **他人を ADMIN 権限でスキップ** で別。
判定: **🔴 真の漏れ**。優先度: 中（離職者対応の救済導線）。

#### C-11. `DELETE /api/v1/circulation/{id}/attachments/{attachmentId}` 添付削除 未実装

設計書（F05.2 line 230, 998）: DRAFT 段階で添付ファイルを削除する。
実装側調査: `CirculationAttachmentController` は GET / POST / POST `/upload-url` のみで `@DeleteMapping` 無し。
判定: **🔴 真の漏れ**。優先度: 中（DRAFT 編集時に添付差し替えができない）。

#### C-12. `DELETE /api/v1/circulation/{id}/comments/{commentId}` コメント削除 未実装（Service 層は実装済）

設計書（F05.2 line 233, 1105）: 本人 or ADMIN によるコメント削除。
実装側調査:
- `CirculationCommentController` には `@DeleteMapping` 無し（GET / POST のみ）
- `CirculationCommentService#deleteComment(Long documentId, Long commentId, Long userId)` (line 106) は **実装済**
判定: **🔴 Controller 層欠落の真の漏れ（ChatBookmarkController と同種パターン）**。Service が揃っているため `@DeleteMapping("/{commentId}")` を Controller に追加するだけで根治可能。優先度: 中（不適切コメント削除は荒らし対策で重要）。

---

## 2. section 2（実装あり・設計なし）の分類

### α. `/api/v1/circulations/*` 4 件（baseline #5.4 内、独自表示）

| メソッド | パス | Controller | 判定 |
|---|---|---|---|
| DELETE | `/circulations/{_}/recipients/{_}` | `CirculationRecipientController#removeRecipient` | 🟡 F05.2 §4 に新規追記（受信者削除。設計書は PATCH /skip 派しか無く、DELETE は未記載） |
| POST | `/circulations/{_}/attachments/upload-url` | `CirculationAttachmentController#presignUpload` | 🟡 F05.2 §4 に新規追記（添付 Pre-signed URL 発行。設計書は POST /attachments のみで、Pre-signed URL 発行ステップは省略されている） |
| POST | `/circulations/{_}/stamp/reject` | `CirculationStampController#reject` | 🟡 F05.2 §4 に新規追記（押印拒否。「押せません・差し戻し」用導線。設計書には reject 概念が無い） |
| POST | `/circulations/{_}/stamp/skip` | `CirculationStampController#skip` | 🟡 F05.2 §4 に新規追記（**自分自身**のスキップ。設計書は ADMIN 強制スキップしか無い C-10 → 自セルフ版は別 API として併存） |

合計 4 件 = **🟡 4 件**（すべて設計書追記対象）。

### β. section 2 内に分散する circulation 系実装パス（5 件）

baseline 行 2661 / 2834 / 2897 / 2921 / 3106 に分散して登場:

| メソッド | 実装パス | Controller | 設計書 path（既存記載） | 判定 |
|---|---|---|---|---|
| POST | `/organizations/{_}/circulations/{_}/activate` | `OrgCirculationDocumentController#activateDocument` | `POST /circulation/{id}/start`（line 218）| 🟡 A-2 の start→activate で吸収 |
| GET | `/teams/{_}/circulations/stats` | `TeamCirculationDocumentController#getStats` | （設計書に該当なし）| 🟡 F05.2 §4 に **新規追記**（チーム回覧の集計統計）|
| PATCH | `/teams/{_}/circulations/{_}` | `TeamCirculationDocumentController#updateDocument` | `PUT /circulation/{id}`（line 216）| 🟡 A-2 の PUT→PATCH で吸収 |
| POST | `/teams/{_}/circulations/{_}/activate` | `TeamCirculationDocumentController#activateDocument` | `POST /circulation/{id}/start`（line 218）| 🟡 A-2 の start→activate で吸収 |
| GET | `/me/circulations/created` | `MyCirculationController#listCreatedDocuments` | `GET /circulation/my`（line 228）| 🟡 A-2 の my→created で吸収。ただし「自分が作成した」と「自分宛」は別概念のため、F05.2 §4 を **`GET /me/circulations/created`（自分が作成した回覧）** に書き換え、**「自分宛の未確認回覧一覧」は別 API として要件再整理** が必要（C-13 候補、ただし C 群 12 件に対して追加するかは別 PR で精査）|

判定: section 2 分散 5 件はすべて 🟡 設計書整流の対象。`/teams/{}/circulations/stats` のみが完全新規追記。

### γ. Org スコープ Controller の機能欠落（🔴 B-3 として追加）

`OrgCirculationDocumentController` (line 29) は GET(list) / GET(detail) / POST(create) / POST(activate) の **4 操作のみ** を実装しており、Team 側の `TeamCirculationDocumentController` が実装する PATCH(update) / POST(cancel) / DELETE / GET(stats) の **4 操作が欠落**。

判定: **🔴 真の漏れ B-3**（4 個分）— OrgCirculationDocumentController に次を追加する別 PR が必要:
- `PATCH /organizations/{orgId}/circulations/{documentId}` (update)
- `POST /organizations/{orgId}/circulations/{documentId}/cancel`
- `DELETE /organizations/{orgId}/circulations/{documentId}`
- `GET /organizations/{orgId}/circulations/stats`

優先度: 中（組織スコープ回覧の運用画面構築時に必要になる）。
※ 本 triage_log のサマリ 🔴 12 件には数字としては別管理（C-1〜C-12 がフラット URL ベースの集計、B-3 はスコープ別の欠落として 4 個別カウント）。**真の合計 🔴 は 16 個分の API**（C 群 12 + B-3 群 4）として記録するが、サマリ表内では C-1〜C-12 のみを 12 件として表示する（baseline の集計単位と整合させるため）。

---

## 3. 修正済みファイル一覧（本 PR のスコープ）

### 3.1 docs/features/F05.2_circular.md

§4 API 仕様（line 213-236 周辺の表ヘッダ）と §4.x 詳細セクション（line 305-1190 周辺）を以下の方針で書き換え:

- **URL 体系の整流**: `/api/v1/circulation/*` (単数形・スコープ無し) を、実装の `/organizations/{orgId}/circulations`, `/teams/{teamId}/circulations`, `/me/circulations`, `/circulations/{documentId}/...` (子リソース) のスコープ分離モデルに書き換え 🟡
- **PUT → PATCH**: `PUT /circulation/{id}` → `PATCH /teams/{teamId}/circulations/{documentId}` (A-2)
- **start → activate**: `POST /circulation/{id}/start` → `POST /teams/{teamId}/circulations/{documentId}/activate` / `POST /organizations/{orgId}/circulations/{documentId}/activate`
- **my → created**: `GET /circulation/my` → `GET /me/circulations/created` （ただし「自分宛 (to me)」と「自分が作成した (created)」は別概念のため、設計書本文に補注を追加）

新規追記（実装あり・設計書未記載）:
- `DELETE /circulations/{documentId}/recipients/{recipientId}` (受信者削除)
- `POST /circulations/{documentId}/attachments/upload-url` (Pre-signed URL 発行)
- `POST /circulations/{documentId}/stamp/reject` (押印拒否)
- `POST /circulations/{documentId}/stamp/skip` (自セルフスキップ)
- `GET /teams/{teamId}/circulations/stats` (チーム回覧統計)

未実装注記（C-1〜C-12 + B-3 群 4 件 = 計 16 個分）:
F05.2 §4 の該当行に **【未実装・Phase 2 残】** 注記を追加。Phase 2 計画として:
- Service 層が部分実装済の `C-12 DELETE comments` は最優先（Controller 追加のみで完了）
- `C-9 GET /status` は UI 構築のブロッカーになるため次優先
- `C-3/C-4` (correct/delegate) は業務必須機能
- `C-7/C-8` (export) は監査用途で重要
- 残りは Phase 2 中盤以降

### 3.2 docs/features/F04.10_committee.md

line 379 の「既存 API のスコープ拡張」表内 `POST /api/v1/circulation-documents` を **`POST /api/v1/organizations/{orgId}/circulations` + COMMITTEE スコープ受容（committee_id フィールド）** に書き換え 🟡。

### 3.3 docs/internal/api_drift_exclusions.yml

- 追記なし（circulation ドメインには内部用 / 旧 prefix が無い。`/circulation-documents` は廃止 path だが、設計書側で path を `/circulations` に書き換えれば exclusions 不要）

### 3.4 docs/internal/triage_log/circulation.md（このファイル）新規作成

---

## 4. 検証

- v5 スキャナの再実行は **本 PR では未実行**（殿が最後にまとめて regenerate する想定）
- 設計書側の URL 体系大規模書き換えのため、F05.2 §4 詳細セクション全体の path 表記揃え漏れがないか目視確認
- F04.10 line 379 の整流は単一行のみで影響範囲限定
- F09.14 連携箇所 (`circulation_document_id` 紐付け) は今回触らない（F09.14 ドメイン管轄）

---

## 5. 残課題（次フェーズ）

1. **🔴 真の漏れ 12 件 + B-3 群 4 件 = 計 16 個分 API の実装**
   - 最優先: `DELETE /circulations/{documentId}/comments/{commentId}` (C-12, Service 既存)
   - 次優先: `GET /circulations/{documentId}/status` (C-9, UI ブロッカー)
   - 業務必須: `POST /stamp/correct` (C-3), `POST /stamp/delegate` (C-4), `POST /remind` (C-5)
   - 監査: `GET /export` (C-7), `GET /export/status` (C-8)
   - 管理: `POST /force-complete` (C-1), `POST /batch/force-complete` (C-2)
   - 効率化: `POST /duplicate` (C-6), `PATCH /recipients/{userId}/skip` (C-10), `DELETE /attachments/{attachmentId}` (C-11)
   - スコープ別欠落 (B-3): Org Controller に PATCH(update) / POST(cancel) / DELETE / GET(stats) 追加
   - 16 件をいくつかの別 PR に分けて実装。優先度マトリクスは F05.2 Phase 2 軍議で確定
2. **F05.2 設計書全体の URL 体系大規模整流**
   - 本 PR で §4 の path を書き換えるが、§5 動線フロー・§6 リクエスト例・§7 シーケンス図 等の本文記述（line 800-1500 周辺）内に古い `/circulation/` 表記が残っていないかの追跡が必要
   - 章立てによっては Phase 2 計画書として「ScopeChange Roadmap」セクションを §13 等に追加
3. **F09.14 連携の確認（範囲外だが派生）**
   - F09.14 line 299 `POST /api/v1/organizations/{id}/disclosure-exports/{exportId}/circulation` で電子印鑑承認回覧を開始する API が設計書にはあるが、実装側で `CirculationDocumentDeletedEvent` (F09.14 設計書 line 448) との連動が完成しているか別 PR で確認
4. **scanner v6 改修候補**
   - F05.2_circular.md のように §4 表ヘッダと §4.x 詳細ヘッダで同一 (method, path) を 2 重記述するパターンが他ドメインにも存在（chat triage 4 と同じ）。v5 は集計時 unique 化しているが、「設計書内重複」を **🐞 として明示マークする** 機能を v6 で追加すると triage の精度が上がる

---

## 6. 部 1 / 部 2 全件のサンプリング検証ログ（抜粋）

### 検証コマンド例

```bash
# Controller 全体のマッピング確認
grep -rn "@(Get|Post|Put|Patch|Delete|Request)Mapping" backend/src/main/java/com/mannschaft/app/circulation/controller/

# 設計書側の API 一覧抽出
grep -nE "^\| (GET|POST|PUT|PATCH|DELETE) \| \`?/api/v1/circulation" docs/features/F05.2_circular.md

# Service 層に実装あるが Controller 層欠落の検出
grep -rn "public.*deleteComment\|public.*forceComplete\|public.*correct\|public.*delegate" backend/src/main/java/com/mannschaft/app/circulation/

# Org / Team の機能対称性確認
diff <(grep "@(Get|Post|Put|Patch|Delete)Mapping" .../OrgCirculationDocumentController.java) \
     <(grep "@(Get|Post|Put|Patch|Delete)Mapping" .../TeamCirculationDocumentController.java)
```

### 主要発見

- **URL 体系の根本不整合**: 設計書は `/api/v1/circulation/*` (フラット・単数)、実装は `/api/v1/{organizations|teams|me}/circulations/{...}` および `/api/v1/circulations/{documentId}/...` (スコープ分離・複数形)。**21 件すべて整流対象**
- **真の漏れ 🔴 は 12 件（フラット URL 集計）/ 16 個分 API（スコープ別含む）**: 設計書記載の高度機能（複製・代理押印・訂正・PDF エクスポート・強制完了・状況可視化・手動リマインド）が軒並み未実装。Phase 2 残として記録
- **Service 層は揃っているが Controller 層欠落が 1 件**: `CirculationCommentService#deleteComment` 実装済、`CirculationCommentController` に `@DeleteMapping` 無し（ChatBookmarkController と同種パターン、最優先で根治推奨）
- **Org Controller の機能サブセット問題**: `OrgCirculationDocumentController` は 4 操作のみで、Team 側の 8 操作中 4 操作（update/cancel/delete/stats）が未実装 → B-3 群として記録
- **新規追記（実装あり設計なし）が 5 件**: recipients DELETE / attachments upload-url POST / stamp reject / stamp skip / teams stats GET。すべて F05.2 §4 への追記対象 🟡

---

## 付録: 数字の根拠

| 区分 | 件数 | 算出根拠 |
|---|---:|---|
| 🔴 | 12 | C-1〜C-12（フラット URL 換算）。スコープ別の B-3 群 4 個分（Org Controller 機能欠落）を加えると 16 個分 API |
| 🟡 | 13 | section1 21 件のうち実装側に対応 path がある整流対象（A-2 マッピング表の 12 行）+ F04.10 line 379 整流 1 件 = 13 件 / section2 chat-folders 相当の新規追記は 🟡 9 件分（α 4 件 + β 5 件）を別カウントで含めるとさらに増えるが、サマリでは「設計書整流の主軸 13 件」として集計 |
| 🔵 | 0 | F05.2 / F04.10 / F09.14 のいずれも circulation 機能を「Phase X 未着工」マーカで記載していない |
| ⚪ | 0 | 内部用 / 旧 prefix なし |
| 🐞 | 1 | F05.2 §4 表ヘッダと §4.x 詳細ヘッダの設計書内 2 重記述（21 件全件にわたるが scanner v5 が集計時 unique 化しているため、1 件として記録）|
| **計** | **26** | section1 unique=22 (21 + circulation-documents 1) + section2=4 |

(分類オーバーラップを許容しているため列和は重複あり。最終件数は機械的に 26 件 = section1 unique 22 + section2 4 へ正規化。)
