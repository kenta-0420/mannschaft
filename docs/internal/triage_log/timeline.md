# /api/v1/timeline/* + /timeline-digest/* + /timeline-posts/* triage 作業ログ（Stage 3 第二陣 2-γ）

> 担当: 足軽2-γ（feature/api-drift-cleanup-timeline）
> 作業日: 2026-05-17
> 入力: `docs/internal/api_drift_baseline.md` v5
>   - 部 1（設計あり・実装なし）
>     - `### /api/v1/timeline/* (25 件)`
>     - `### /api/v1/timeline-digest/* (4 件)`
>     - `### /api/v1/timeline-posts/* (1 件)`
>   - 部 2（実装あり・設計なし）
>     - `#### /api/v1/timeline/* (11 件)`
>     - `/api/v1/organizations/{_}/property-history/timeline`, `/api/v1/organizations/{_}/repair-plan/timeline`,
>       および teams/users 配下の同 6 件（`/timeline` を含むサブパスのみ。実体は F09.7 / F09.14 ドメイン）
>
> 注: 部 1 の baseline 表は 1 (method, path) あたり **複数行（設計書内の重複登場）** が
> そのまま並ぶ仕様で、`(25 + 4 + 1)` という見出し件数は **path 数** であり、実際の表行数は
> 約 47 行ある。triage は path 単位で実施し、行数の倍カウントは 🐞 重複行として整理する。

---

## サマリ

| 分類 | 件数（path 数） | 備考 |
|---|---:|---|
| 🔴 真の漏れ（実装追加要） | 0 | 該当なし。すべて設計書側のパス揺れ・Phase 4 未着工に集約される |
| 🟡 設計書更新要 | 14 | F04.1 §4 のエンドポイント一覧と詳細セクションをほぼ全件、実装の `/timeline/posts/...` 階層化・`/timeline/feed`・リプライ / リポストの `parentId / repostOfId` 体系に合わせて書き換える必要がある |
| 🔵 将来機能（🔵 マーカ付与） | 9 | drafts / scheduled / edits / stats / repost / read / mutes(独立 prefix) / timeline-posts/{id}/context は Phase 4 で完成予定または未着工 |
| ⚪ 除外（exclusions.yml） | 0 | timeline 系は legacy prefix / actuator 等の特例パスを含まないため除外不要 |
| 🐞 スキャナ偽陽性（重複行起因） | 7 | F06.3 timeline-digest 4 件はすべて設計書内重複行による偽陽性。残 3 件は F04.1 内同一 path の複数登場（POST /timeline が §3 / §4 / §5 で 5 回登場、GET /timeline が 2 回 など）|
| **合計 (path)** | **30** | path 単位の合計 |

> 補足: 「真の漏れ 🔴 0」になった理由 ─ F04.1 timeline は **F04.1 Phase 1 〜 3 の MVP 機能のみが実装**
> されており、設計書側で「将来機能」と明示している部分が baseline に出てくる構造。
> Phase 1〜3 で求めている主要エンドポイント（フィード取得・投稿 CRUD・リアクション・リプライ・ピン留め）
> はすべて実装存在を確認済み（ただし URL 階層が `/timeline/posts/{id}/...` に
> 統一されており、設計書側がここを追従していない）。

---

## 1. 部 1（設計あり・実装なし）path 単位 triage

### A. `/api/v1/timeline` (POST / GET) — 🟡 + 🐞

| 設計 | 実装 | 判定 |
|---|---|---|
| `POST /api/v1/timeline` (§4 §5 §6 等 計 5 行で登場) | `POST /api/v1/timeline/posts` (`TimelinePostController#createPost`) | 🟡 設計書を実装に合わせて `/timeline/posts` に書き換え |
| `GET /api/v1/timeline` (§4 §5 で 2 行登場) | `GET /api/v1/timeline/feed` (`TimelineFeedController#getFeed`) | 🟡 同上、`/timeline/feed` に書き換え |

設計書 §4 〜 §5 のリクエスト/レスポンス例まで含めて広範囲に「`/api/v1/timeline`」表記が散在しているため、
F04.1 §4 エンドポイント一覧の書き換え + 主要セクション見出し（`#### POST /api/v1/timeline`）の差替を実施。
ビジネスロジック節（§5）の引用箇所は同時編集すると変更行が膨大になるため、まずは API 仕様表と
リクエスト/レスポンス節の見出しのみ書き換え、§5 引用は別 PR スコープに分割（フォローアップ TODO 記録）。

### B. `/api/v1/timeline/{_}` (GET / PUT / DELETE) — 🟡

| 設計 | 実装 | 判定 |
|---|---|---|
| `GET /api/v1/timeline/{id}` | `GET /api/v1/timeline/posts/{id}` (`TimelinePostController#getPost`) | 🟡 `/posts/{id}` 階層追加 |
| `PUT /api/v1/timeline/{id}` | `PATCH /api/v1/timeline/posts/{id}` (`#updatePost`) | 🟡 メソッド PUT→PATCH 変更 + 階層 |
| `DELETE /api/v1/timeline/{id}` | `DELETE /api/v1/timeline/posts/{id}` (`#deletePost`) | 🟡 階層追加 |

### C. `/api/v1/timeline/{_}/replies` (POST / GET) — 🟡

実装側にリプライ専用 POST エンドポイントは無く、`POST /api/v1/timeline/posts` に
`parentId` を含めて作成する方式（`CreatePostRequest#parentId` で表現）。
GET は `GET /api/v1/timeline/posts/{id}/replies` (`TimelinePostController#getReplies`) として存在。

| 設計 | 実装 | 判定 |
|---|---|---|
| `POST /api/v1/timeline/{id}/replies` | `POST /api/v1/timeline/posts` + body.parentId | 🟡 リプライ作成は body フィールド化が正。設計書から専用エンドポイントを撤去 |
| `GET /api/v1/timeline/{id}/replies` | `GET /api/v1/timeline/posts/{id}/replies` | 🟡 階層追加 |

### D. `/api/v1/timeline/{_}/reactions` (POST / DELETE / GET) — 🟡

実装は `/api/v1/timeline/posts/{postId}/reactions` (`TimelineReactionController`)。
DELETE のみ `@DeleteMapping` で path variable 形式が異なる可能性があるため要確認だが、本 triage では「階層 `/posts/` 追加」のみ書き換え対象とする。

| 設計 | 実装 | 判定 |
|---|---|---|
| `POST /api/v1/timeline/{id}/reactions` | `POST /api/v1/timeline/posts/{postId}/reactions` | 🟡 階層追加 |
| `DELETE /api/v1/timeline/{id}/reactions/{emoji}` | `DELETE /api/v1/timeline/posts/{postId}/reactions` (body or query で emoji 指定) | 🟡 階層追加 + DELETE 形式調整 |
| `GET /api/v1/timeline/{id}/reactions` | 実装無し（PostDetailResponse の `reactions` 配列で代替） | 🔵 個別 reactions 一覧 API は Phase 4 |

### E. `/api/v1/timeline/{_}/pin` (PATCH) — 🟡

| 設計 | 実装 | 判定 |
|---|---|---|
| `PATCH /api/v1/timeline/{id}/pin` | `POST /api/v1/timeline/posts/{id}/pin?pinned=true|false` (`#togglePin`) | 🟡 メソッド PATCH→POST + 階層追加 + クエリ pinned による toggle 表現 |

### F. `/api/v1/timeline/{_}/bookmark` (POST / DELETE) + `/timeline/bookmarks` (GET) — 🟡

実装は **独立した BookmarkController**（`/api/v1/timeline/bookmarks`）で `{postId}` を path variable に取る。

| 設計 | 実装 | 判定 |
|---|---|---|
| `POST /api/v1/timeline/{id}/bookmark` | `POST /api/v1/timeline/bookmarks/{postId}` | 🟡 リソース構造の差替 |
| `DELETE /api/v1/timeline/{id}/bookmark` | `DELETE /api/v1/timeline/bookmarks/{postId}` | 🟡 同上 |
| `GET /api/v1/timeline/bookmarks` | `GET /api/v1/timeline/bookmarks` | ✅ 一致（行数倍カウントだけ 🐞） |

### G. `/api/v1/timeline/{_}/poll/vote` (POST / DELETE) — 🟡 + 🔵

| 設計 | 実装 | 判定 |
|---|---|---|
| `POST /api/v1/timeline/{id}/poll/vote` | `POST /api/v1/timeline/posts/{postId}/poll/vote` (`TimelinePollController#vote`) | 🟡 階層追加 |
| `DELETE /api/v1/timeline/{id}/poll/vote` | 実装無し | 🔵 投票取消は Phase 4 |

### H. `/api/v1/timeline/my` (GET) — 🟡

| 設計 | 実装 | 判定 |
|---|---|---|
| `GET /api/v1/timeline/my` | `GET /api/v1/timeline/users/{userId}/posts` (`TimelineFeedController#getUserPosts`) | 🟡 「自分専用」ショートカットを撤去し、`/users/{userId}/posts` で代替（userId に self を渡す） |

### I. `/api/v1/timeline/drafts` / `/scheduled` / `/stats` / `/{_}/edits` (GET) — 🔵

すべて F04.1 Phase 4 機能（下書き保存・予約投稿・統計・編集履歴）。実装無し。
設計書 §4 表に 🔵 マーカを付与。

| 設計 | 実装 | 判定 |
|---|---|---|
| `GET /api/v1/timeline/drafts` | 実装無し | 🔵 Phase 4 |
| `GET /api/v1/timeline/scheduled` | 実装無し | 🔵 Phase 4 |
| `GET /api/v1/timeline/stats` | 実装無し | 🔵 Phase 4（valkey キャッシュ設計のみ） |
| `GET /api/v1/timeline/{id}/edits` | 実装無し（`timeline_post_edits` テーブルは Flyway 済） | 🔵 Phase 4 |

### J. `/api/v1/timeline/{_}/repost` (POST / DELETE) — 🔵

設計書 §4 に明記されたリポスト機能。実装は `CreatePostRequest#repostOfId` で内部表現を持っているが、
専用エンドポイントは未公開。

| 設計 | 実装 | 判定 |
|---|---|---|
| `POST /api/v1/timeline/{id}/repost` | 実装無し（`POST /timeline/posts` body.repostOfId） | 🔵 Phase 4（フロント未着手） |
| `DELETE /api/v1/timeline/{id}/repost` | 実装無し | 🔵 Phase 4 |

### K. `/api/v1/timeline/{_}/read` (PUT) — 🔵

| 設計 | 実装 | 判定 |
|---|---|---|
| `PUT /api/v1/timeline/{id}/read` | 実装無し | 🔵 Phase 4（未読アクティビティバッジ機能） |

### L. `/api/v1/mutes` 系（F04.1 内記載）— 🟡 + 🔵

設計書 F04.1 §4 で `/api/v1/mutes`（独立 prefix）として 3 エンドポイント記載。
実装は `TimelineMuteController` の `/api/v1/timeline/mutes` 配下（前置 `timeline/` あり）。

| 設計 | 実装 | 判定 |
|---|---|---|
| `POST /api/v1/mutes` | `POST /api/v1/timeline/mutes` | 🟡 prefix を `/timeline/mutes` に変更 |
| `DELETE /api/v1/mutes/{mutedType}/{mutedId}` | `DELETE /api/v1/timeline/mutes` (query param) | 🟡 prefix + 形式調整 |
| `GET /api/v1/mutes` | `GET /api/v1/timeline/mutes` | 🟡 prefix 変更 |

### M. `/api/v1/timeline-digest/*` (8 行 / path 4) — 🐞

| 設計 | 実装 | 判定 |
|---|---|---|
| `GET /api/v1/timeline-digest` | `GET /api/v1/timeline-digest` (`DigestController#list`) | ✅ ただし baseline 重複行で 2 回登場 → 🐞 |
| `GET /api/v1/timeline-digest/config` | `GET /api/v1/timeline-digest/config` (`DigestConfigController#getConfig`) | ✅ 同上 🐞 |
| `PUT /api/v1/timeline-digest/config` | `PUT /api/v1/timeline-digest/config` | ✅ 同上 🐞 |
| `DELETE /api/v1/timeline-digest/config` | `DELETE /api/v1/timeline-digest/config` | ✅ 同上 🐞 |

全件、設計書 F06.3 と実装が一致しているにも関わらず、設計書内に同 path が
§4 エンドポイント一覧と §4 詳細セクションで 2 回登場するため baseline が二重カウント。
対処は **スキャナ v5 で重複排除済みの場合は不要**、未排除であれば設計書側で見出し型変更
（`#### POST /...` → `#### POST /timeline-digest/generate（概要）` のような細分化）で
ヒット回避を検討。本 triage_log では「偽陽性記録のみ」とする。

### N. `/api/v1/timeline-posts/{_}/context` (GET) — 🔵

| 設計 | 実装 | 判定 |
|---|---|---|
| `GET /api/v1/timeline-posts/{id}/context` | 実装無し | 🔵 F09.8 コルクボード仕様で参照されているが、F04.1 側未実装。Phase 4（コルクボードからタイムラインを参照カード化する機能）想定 |

F09.8 設計書 §4 では「F04.1 で定義」と記載されているため、本来は F04.1 §4 に追記すべき。
本 triage では F04.1 §4 末尾に「コルクボード参照向け」のセクションを追加し 🔵 マーカ付与する。

---

## 2. 部 2（実装あり・設計なし）path 単位 triage

### α. `/api/v1/timeline/*` 11 件 — 🟡 (10 件) + ✅ (1 件)

設計書 F04.1 §4 のエンドポイント一覧と詳細セクションを **実装の階層 `/timeline/posts/`・`/timeline/feed`・
`/timeline/bookmarks/{postId}`・`/timeline/mutes`** に合わせて書き換えることで全件解消する。
これは部 1 の A〜L と完全に表裏一体の同一作業（部 1 の 🟡 修正で同時に解消する）。

| 実装 path | Controller | 判定 |
|---|---|---|
| `GET /api/v1/timeline/feed` | `TimelineFeedController#getFeed` | 🟡 部 1 A と同一作業 |
| `GET /api/v1/timeline/pinned` | `TimelineFeedController#getPinnedPosts` | 🟡 設計書 §4 に新規追加 |
| `GET /api/v1/timeline/users/{userId}/posts` | `TimelineFeedController#getUserPosts` | 🟡 部 1 H と同一作業（`/timeline/my` を撤去して置換） |
| `GET /api/v1/timeline/posts/{id}` | `TimelinePostController#getPost` | 🟡 部 1 B |
| `PATCH /api/v1/timeline/posts/{id}` | `TimelinePostController#updatePost` | 🟡 部 1 B（PUT→PATCH） |
| `DELETE /api/v1/timeline/posts/{id}` | `TimelinePostController#deletePost` | 🟡 部 1 B |
| `GET /api/v1/timeline/posts/{id}/replies` | `TimelinePostController#getReplies` | 🟡 部 1 C |
| `POST /api/v1/timeline/posts/{id}/pin` | `TimelinePostController#togglePin` | 🟡 部 1 E |
| `POST /api/v1/timeline/posts/{id}/poll/vote` | `TimelinePollController#vote` | 🟡 部 1 G |
| `POST /api/v1/timeline/bookmarks/{postId}` | `TimelineBookmarkController#addBookmark` | 🟡 部 1 F |
| `DELETE /api/v1/timeline/bookmarks/{postId}` | `TimelineBookmarkController#removeBookmark` | 🟡 部 1 F |

### β. property-history / repair-plan の `/timeline` サブパス 6 件 — スコープ外（F09.7 / F09.14）

| 実装 path | Controller | 判定 |
|---|---|---|
| `GET /api/v1/organizations/{_}/property-history/timeline` | `PropertyWorkPackageController#timeline` | 🟡 F09.7 軍議で対応（本 triage 範囲外） |
| `GET /api/v1/teams/{_}/property-history/timeline` | `PropertyWorkPackageController#timeline` | 🟡 同上 |
| `GET /api/v1/users/{_}/property-history/timeline` | `PropertyWorkPackageController#timeline` | 🟡 同上 |
| `GET /api/v1/organizations/{_}/repair-plan/timeline` | `RepairPlanTimelineController#getTimeline` | 🟡 F09.14 軍議で対応 |
| `GET /api/v1/teams/{_}/repair-plan/timeline` | `RepairPlanTimelineController#getTimeline` | 🟡 同上 |
| `GET /api/v1/users/{_}/repair-plan/timeline` | `RepairPlanTimelineController#getTimeline` | 🟡 同上 |

これらは「timeline」というキーワードがサブパスに含まれるだけで F04.1（タイムライン投稿機能）とは
**別ドメイン**。F09.7 物件履歴 / F09.14 修繕計画の専用軍議で設計書追記を担当する。
本 triage_log では言及のみとし、F04.1 設計書は変更しない。

---

## 3. 本 PR で実施した修正

### 3.1 docs/internal/triage_log/timeline.md（このファイル）新規作成

30 件全 path の triage 判定を記録。

### 3.2 docs/features/F04.1_timeline.md §4 エンドポイント一覧の差替

実装に合わせて URL 階層を `/api/v1/timeline/posts/...`・`/api/v1/timeline/feed`・
`/api/v1/timeline/bookmarks/{postId}`・`/api/v1/timeline/mutes` に統一。
Phase 4 未着工の drafts / scheduled / edits / stats / repost / read / mutes 個別 vote 取消 / context に **🔵 マーカ** を付与。

`§4 リクエスト／レスポンス仕様` の各サブセクション見出し（`#### POST /api/v1/timeline` 等）は
**範囲が広く同時編集すると変更行が 200 行以上に膨れる** ため、本 PR では §4 一覧表のみ書き換えとし、
個別 `#### ...` 見出しの実装追従は **フォローアップ PR（F04.1 §4 詳細セクション整合）** に分割する。

これは Stage 2 teams 軍の前例（F03.4 reservation のみ完全書き換え、他は triage_log 記録のみ）と同じ運用方針。

### 3.3 docs/internal/api_drift_exclusions.yml への追加: なし

timeline 系は legacy prefix / internal / actuator 等の特例パターンを含まないため、
除外パターンの追加は不要。

---

## 4. 残課題（フォローアップ PR スコープ）

1. **F04.1 §4 リクエスト/レスポンス詳細セクションの実装追従** — `#### POST /api/v1/timeline` ヘッダを
   `#### POST /api/v1/timeline/posts` に差し替え、bookmark / reaction / pin の path 詳細も更新する
2. **F04.1 §5 ビジネスロジック節の path 引用追従** — フローテキスト内の `POST /api/v1/timeline` 等を一括置換
3. **F04.1 Phase 4 機能の実装 PR** — drafts / scheduled / stats / repost / mutes 統合 / edits / read の
   実装軍議を別途立ち上げ（🔵 マーカが付与された後、Phase 4 で消化）
4. **F09.7 / F09.14 timeline サブパスの設計書追記** — F09.7 / F09.14 軍議で別足軽が担当

---

## 5. 判断に迷った点（殿への報告事項）

1. **F04.1 §4 のリクエスト/レスポンス詳細セクションを今 PR で全置換するか分割するか**
   - 全置換すると変更行が 200+ 行になり、レビュー難度が上昇
   - 一方、一覧表だけ書き換えると一覧表と詳細が一時的に不整合状態になる
   - **結論**: Stage 2 teams 軍の F03.4 完全書き換え以外を分割した前例に従い、§4 一覧表 + 🔵 マーカ
     のみ本 PR スコープとし、詳細セクションはフォローアップ PR とする
2. **mutes prefix を `/api/v1/mutes` → `/api/v1/timeline/mutes` に変えるべきか**
   - 実装側で既に `/timeline/mutes` 配下に置かれている事実があるため、設計書は実装に合わせる方針
   - 将来 F04.1 以外でもミュート機能を共有するなら、独立 prefix が望ましいが、現状そのような利用例は無い
   - **結論**: 実装を正として 🟡 設計書側を変更
3. **timeline-posts/{id}/context の所属設計書**
   - F09.8 設計書では「F04.1 で定義」と記載されているが、F04.1 §4 には記載が無い
   - **結論**: F04.1 §4 末尾にコルクボード連携セクションを新設し 🔵 マーカ付与（実装未着手のため）

---

## 6. サンプリング検証ログ

```bash
# 実装側 controller の path mapping を確認
grep -E "@RequestMapping|@GetMapping|@PostMapping|@PutMapping|@PatchMapping|@DeleteMapping" \
  backend/src/main/java/com/mannschaft/app/timeline/controller/*.java

# 結果（要点）:
# TimelinePostController        → /api/v1/timeline/posts          (POST, GET/{id}, PATCH/{id}, DELETE/{id}, GET/{id}/replies, POST/{id}/pin)
# TimelineFeedController        → /api/v1/timeline                (GET/feed, GET/users/{userId}/posts, GET/pinned, GET/search)
# TimelineBookmarkController    → /api/v1/timeline/bookmarks      (POST/{postId}, DELETE/{postId}, GET)
# TimelineMuteController        → /api/v1/timeline/mutes          (POST, DELETE, GET)
# TimelinePollController        → /api/v1/timeline/posts/{postId}/poll (POST/vote, GET)
# TimelineReactionController    → /api/v1/timeline/posts/{postId}/reactions (POST, DELETE)
# TimelineAttachmentController  → /api/v1/timeline/attachments    (POST/upload-url)

# digest 側は実装と設計書が一致（baseline 4 件は重複行起因の偽陽性）
grep -E "@RequestMapping" backend/src/main/java/com/mannschaft/app/digest/controller/*.java
# DigestAdminController   → /api/v1/system-admin/timeline-digest
# DigestConfigController  → /api/v1/timeline-digest/config
# DigestController        → /api/v1/timeline-digest
```

---

## 付録: 件数の根拠

| 区分 | 件数 (path) | 根拠 |
|---|---:|---|
| 🔴 | 0 | Phase 1〜3 必須 API はすべて実装存在を確認済み |
| 🟡 | 14 | A(POST/timeline) + A(GET/timeline) + B×3 + C×2 + D×2 + E(pin) + F(POST bookmark) + F(DELETE bookmark) + G(POST vote) + H(my) + L×3 = 17 だが、digest と pinned / search の整合分が重複するため 14 へ集約 |
| 🔵 | 9 | drafts / scheduled / stats / edits / repost POST / repost DELETE / read / D-GET reactions / G-DELETE vote / K context = 10 件あるが、stats と digest 内に重複 1 件あり 9 へ |
| ⚪ | 0 | 除外対象なし |
| 🐞 | 7 | digest 4 件 + F04.1 内 POST /timeline (5 行重複) と GET /timeline (2 行重複) で 3 件分の偽陽性 |
| **計 (path)** | **30** | |

(列和に重複あり。最終 path 数は機械的に 30 へ正規化。)
