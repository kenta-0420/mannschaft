# /api/v1/bulletin/* + /api/v1/bulletin-threads/* triage 作業ログ（Stage 3 第三陣 3-δ）

> 担当: 足軽3-δ（feature/api-drift-cleanup-bulletin）
> 作業日: 2026-05-17
> 入力: `docs/internal/api_drift_baseline.md` v5
>   - 部 1（設計あり・実装なし）
>     - `### /api/v1/bulletin/* (18 件)` — path 18 件、表行 38 行（設計書内の重複登場を含む）
>     - `### /api/v1/bulletin-threads/* (1 件)` — path 1 件
>   - 部 2（実装あり・設計なし）
>     - `#### /api/v1/bulletin/* (1 件)` — `GET /api/v1/bulletin/reactions/summary`
>   - 部 4（🟦 スコープ階層プレフィックス逆引き準一致）
>     - bulletin 系で 14 件が既に V4-1 で準一致除外済み（categories/threads 系の DELETE/GET/PUT スコープ展開）
>
> 注: bulletin 設計書 F05.1 は Stage 2 時点で「§4 冒頭にスコープ移行注記」が
> 既に追加されているため、本陣では既存注記を活かしつつ §4 のエンドポイント表自体を
> 実装の `/api/v1/{scopeType}/{scopeId}/bulletin/...` 体系に更新する。

---

## サマリ

| 分類 | 件数（path 数） | 備考 |
|---|---:|---|
| 🔴 真の漏れ（実装追加要） | 0 | 該当なし。実装は揃っており、設計書の URL 表記揺れに集約 |
| 🟡 設計書更新要 | 17 | F05.1 §4 のエンドポイント一覧（全 24 行）を `/api/v1/{scopeType}/{scopeId}/bulletin/...` に書き換え + `bulletins/unread` (F02.2.1) を `/dashboard/unread-threads` に書き換え + `bulletin/reactions/summary` を F05.1 へ追記 |
| 🔵 将来機能（🔵 マーカ付与） | 1 | `GET /api/v1/bulletin-threads/{id}/context` は F09.8 Phase 9 (`FEATURE_V9_ENABLED=false`) の参照カード機能向け。F09.8 設計書側に Phase 9 まで未着工である旨を再確認 |
| ⚪ 除外（exclusions.yml） | 0 | bulletin 系は legacy prefix / actuator 等の特例パスを含まない |
| 🐞 スキャナ偽陽性（重複行起因） | 0 | path 単位で重複行があるが triage 上は path で集約 |
| **合計 (path)** | **18** | 部 1 (19) + 部 2 (1) − bulletin-threads/context 重複なし = 19 ※path 単位で 18+1 |

> 補足: 「真の漏れ 🔴 0」の理由 ─ `BulletinCategoryController` /
> `BulletinReplyController` / `BulletinReadStatusController` / `BulletinThreadController` /
> `BulletinReactionController` の 5 コントローラで bulletin CRUD・既読・返信・リアクションの
> 主要 API は全て実装済みであり、URL prefix が「`/api/v1/{scopeType}/{scopeId}/bulletin/...`」に
> 統一されている。設計書 F05.1 が古い「scope なし」表記のまま追従していないだけ。

---

## 1. 部 1（設計あり・実装なし）path 単位 triage

### A. `/api/v1/bulletin/categories` (GET / POST) — 🟡

| 設計 | 実装 | 判定 |
|---|---|---|
| `GET /api/v1/bulletin/categories` | `GET /api/v1/{scopeType}/{scopeId}/bulletin/categories` (`BulletinCategoryController#listCategories`) | 🟡 設計書を `{scopeType}/{scopeId}` 階層を加える形に書き換え |
| `POST /api/v1/bulletin/categories` | `POST /api/v1/{scopeType}/{scopeId}/bulletin/categories` (`BulletinCategoryController#createCategory`) | 🟡 同上 |

### B. `/api/v1/bulletin/threads` (GET / POST) + 関連サブパス — 🟡

| 設計 | 実装 | 判定 |
|---|---|---|
| `GET /api/v1/bulletin/threads` | `GET /api/v1/{scopeType}/{scopeId}/bulletin/threads` (`BulletinThreadController#listThreads`) | 🟡 |
| `POST /api/v1/bulletin/threads` | `POST /api/v1/{scopeType}/{scopeId}/bulletin/threads` (`BulletinThreadController#createThread`) | 🟡 |
| `GET /api/v1/bulletin/threads/updates` | （実装なし。`/api/v1/{scopeType}/{scopeId}/bulletin/threads` の polling で代替が想定される設計）| 🟡 設計書から「updates」専用エンドポイントを除き、メイン一覧で polling する旨に書き換え |
| `GET /api/v1/bulletin/threads/{id}/readers` | `GET /api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/read-status` (`BulletinReadStatusController#listReaders`) | 🟡 設計書を `/read-status` 階層 + scope に書き換え。`readers` → `read-status` への用語統一 |
| `POST /api/v1/bulletin/threads/{id}/read` | `POST /api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/read-status` (`BulletinReadStatusController#markAsRead`) | 🟡 同上 |
| `POST /api/v1/bulletin/threads/read-all` | （実装なし。markAsRead を全スレッド分繰り返す方式で代替）| 🟡 設計書から `read-all` を除去、または将来機能 🔵 として残す（ペーパーバージョン）。ここでは「次フェーズで実装予定」のため 🔵 マーカ候補 |
| `POST /api/v1/bulletin/threads/{id}/replies` | `POST /api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/replies` (`BulletinReplyController#createReply`) | 🟡 |

### C. `/api/v1/bulletin/threads/{id}/pin` `/lock` `/archive` `/priority` — 🟡

| 設計 | 実装 | 判定 |
|---|---|---|
| `PATCH /api/v1/bulletin/threads/{id}/pin` | `POST /api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/pin` (`BulletinThreadController#togglePin`) | 🟡 メソッド PATCH→POST + scope 階層追加 |
| `PATCH /api/v1/bulletin/threads/{id}/lock` | `POST /api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/lock` (`BulletinThreadController#toggleLock`) | 🟡 同上 |
| `PATCH /api/v1/bulletin/threads/{id}/archive` | `POST /api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/archive` (`BulletinThreadController#archive`) | 🟡 同上 |
| `PATCH /api/v1/bulletin/threads/{id}/priority` | （実装なし。`PUT /threads/{threadId}` で priority を含むまるごと更新方式が実装されている）| 🟡 設計書から `/priority` 専用エンドポイントを除き、`PUT` でまるごと更新に統一 |

### D. `/api/v1/bulletin/replies/{id}` (PUT / DELETE) + ネスト返信 — 🟡

| 設計 | 実装 | 判定 |
|---|---|---|
| `PUT /api/v1/bulletin/replies/{id}` | `PUT /api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/replies/{replyId}` (`BulletinReplyController#updateReply`) | 🟡 階層構造を `/threads/{threadId}/replies/{replyId}` に書き換え + scope 追加 |
| `DELETE /api/v1/bulletin/replies/{id}` | `DELETE /api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/replies/{replyId}` (`BulletinReplyController#deleteReply`) | 🟡 同上 |
| `POST /api/v1/bulletin/replies/{id}/replies` | （実装は単一階層 `/threads/{threadId}/replies` のみ。ネスト返信は body.parentReplyId で表現）| 🟡 設計書から専用ネスト返信エンドポイントを除き、`POST /threads/{threadId}/replies` + body フィールドに統一 |

### E. `/api/v1/bulletin/{targetType}/{targetId}/reactions` (POST / DELETE) — 🟡

| 設計 | 実装 | 判定 |
|---|---|---|
| `POST /api/v1/bulletin/{targetType}/{targetId}/reactions` | `POST /api/v1/bulletin/reactions` (`BulletinReactionController#addReaction` — body に `targetType`, `targetId`) | 🟡 設計書を body フィールド方式に書き換え |
| `DELETE /api/v1/bulletin/{targetType}/{targetId}/reactions/{emoji}` | `DELETE /api/v1/bulletin/reactions` (body または query で targetType/targetId/emoji) | 🟡 同上 |

> 補足: BulletinReactionController のみ `@RequestMapping("/api/v1/bulletin/reactions")` で
> scope パラメータを取らない設計。リアクションは「対象（thread or reply）の id だけで一意」と
> 判定できるため、scope を URL から省いた実装。設計書側もこの方針に揃える。

---

## 2. 部 1: `/api/v1/bulletin-threads/* (1 件)`（F09.8 由来）

### F. `GET /api/v1/bulletin-threads/{id}/context` — 🔵

| 設計 | 実装 | 判定 |
|---|---|---|
| `GET /api/v1/bulletin-threads/{id}/context` (F09.8 277行 / 802行 / 911行) | （実装なし）| 🔵 F09.8 コルクボード Phase 9 (FEATURE_V9_ENABLED=false) の参照カード機能向け。コルクボード本体機能と同時に実装予定 |

> F09.8 §1 で「実装フェーズ Phase 9」「Phase 1〜8 は FEATURE_V9_ENABLED=false により UI・API を完全非表示」と
> 明記されている。bulletin-threads/{id}/context、timeline-posts/{id}/context、chat/messages/{id}/context の
> 3 つの context API は揃って Phase 9 未着工。
>
> 対処: F09.8 §4「コンテキスト取得（参照カード用）」表に **状態列**（先頭列）を新設し、3 行とも `🔵` を付与。
> F05.1 側にも「bulletin-threads/{id}/context は F09.8 Phase 9 で実装」と明記する補足を追加検討（ただし
> F05.1 は本来 bulletin 機能本体の設計書であり、F09.8 連携の補助 API は F09.8 が責任を持つため、
> F09.8 側で完結させる方針）。

---

## 3. 部 2（実装あり・設計なし）path 単位 triage

### G. `GET /api/v1/bulletin/reactions/summary` — 🟡

| 実装 | 設計 | 判定 |
|---|---|---|
| `GET /api/v1/bulletin/reactions/summary` (`BulletinReactionController#getReactionSummary`) | F05.1 §4 に reactions 系 API は記載があるが `summary` は未記載 | 🟡 F05.1 §4 の reactions セクションに `GET /reactions/summary`（targetType, targetId をクエリで指定して集計を取得）を追記 |

---

## 4. 関連設計書の他ドメイン由来「設計あり・実装なし」（参考）

bulletin ドメイン外の表に bulletin 関連 path が出ているもの。本 triage では path 自体は
触れず、各ドメインの triage 担当に委ねる。記録のみ。

| 表所属 | path | 言及設計書 | 推奨対応 |
|---|---|---|---|
| `/api/v1/teams/*` (530件 中) | `GET /api/v1/teams/{_}/announcements` | F05.1 (1374行) | F05.1 §7.1 で「announcement 連携を確認する」用途で `/announcements` を呼んでいる記述。announcement (F02.6 / F02.8) ドメイン担当の triage で処理（teams ドメイン担当の third 軍議に委任済み） |
| `/api/v1/teams/*` (530件 中) | `GET /api/v1/teams/{_}/bulletins/unread` | F02.2.1 (568行) | 実装は `/api/v1/dashboard/unread-threads` (`DashboardController#getUnreadThreads`)。F02.2.1 設計書の「実装時にレビューすべき個別 API リスト」表中の URL を実装に整合 (`/api/v1/dashboard/unread-threads`) に書き換える 🟡。本 PR 範囲で実施 |
| `/api/v1/{_}/{_}/* (49件 中)` | `POST /api/v1/{_}/{_}/bulletin/threads` | F02.8 (181行) | 実装は `BulletinThreadController#createThread` (`/api/v1/{scopeType}/{scopeId}/bulletin/threads`)。V5 scanner のスコープ展開で本来準一致になるべきだが `{_}/{_}` プレースホルダの両方を網羅できず残置している scanner 制約事案。F02.8 側で「`{scopeType}` / `{scopeId}`」表記を「`{teamId}` / `{orgId}` で展開可」と明記済みのため、設計書の修正は不要。scanner v6 の課題として記録 |

---

## 5. 設計書編集計画

### F05.1_bulletin_board.md

1. **§4 エンドポイント一覧表（256〜281 行）**: 全 24 行のパスを
   `/api/v1/bulletin/...` → `/api/v1/{scopeType}/{scopeId}/bulletin/...` に書き換え。
   ただし `BulletinReactionController` 系（reactions）は実装が scope を取らないため
   `/api/v1/bulletin/reactions` のまま、ただし body フィールドで target を指定する旨を追記。
2. **§4 メソッド変更**: `PATCH /threads/{id}/pin|lock|archive` → `POST /threads/{threadId}/pin|lock|archive` に修正。
3. **§4 削除**: `/threads/{id}/priority` 単独エンドポイントを削除し、`PUT /threads/{threadId}` でまるごと更新する記述に統一。
4. **§4 追記**: `GET /reactions/summary` を reactions 行群に追加。
5. **§4 削除/🔵 マーカ**: `POST /threads/read-all` を `🔵`（実装なし、次フェーズ実装予定）として残置、または削除。
   今回は「実装なしだが設計上は残しておく価値あり」のため `🔵` を付与する形を採用。
6. **§4 ネスト返信記述変更**: `POST /replies/{id}/replies` を削除し、
   `POST /threads/{threadId}/replies` の body に `parent_reply_id` を含める旨を §4 詳細仕様セクションで明記。
7. **§4 リクエスト/レスポンス仕様セクション（282 行以降）**: 各サブセクションの見出し
   (`#### POST /api/v1/bulletin/categories` 等) も同様に書き換え。本作業は変更行数が膨大に
   なるため、本 PR では §4 のエンドポイント一覧表 + 主要見出しの差替に留め、リクエスト本文中の
   URL 例引用は次の PR で対応する旨を `TODO` コメントで残す。
8. **§7.1 announcement 連携部分（1374 行）**: announcement 担当の triage に委任、本 PR では触れない。
9. **§4 冒頭の「スコープ移行注記」（246〜252 行）**: Stage 2 で追加済み。表書き換え完了後に
   「2026-05-17 に表本体の書き換え完了」「次フェーズで詳細セクション (§4 リクエスト/レスポンス仕様) を
   全件追従」と追記。

### F02.2.1_dashboard_widget_role_visibility.md

568 行の `GET /api/v1/teams/{teamId}/bulletins/unread` を `GET /api/v1/dashboard/unread-threads`
に書き換え（実装に整合）。

### F09.8_corkboard.md

§4「コンテキスト取得（参照カード用）」表（271 行以降）に **状態列**（先頭列）を新設し、
chat/messages/{id}/context、timeline-posts/{id}/context、bulletin-threads/{id}/context の
3 行全てに `🔵` を付与（Phase 9 未着工）。

### F02.8_dashboard_announcement.md

§6 チャネル設計表 181 行の `POST /api/v1/{scopeType}/{scopeId}/bulletin/threads` は実装と整合済み。
変更不要。scanner V5 の `{_}/{_}` 二重スコープ展開で漏れている件は scanner v6 で対応する scanner 課題。

### docs/internal/api_drift_exclusions.yml

bulletin 系で除外パターン追加候補なし。`/api/v1/bulletin/reactions/**` は B案 (実装も設計書化対象) のため、
設計書側を整備して整合させる方針。

---

## 6. PR スコープ

本 PR では以下を実施:

- [x] F05.1 §4 エンドポイント一覧表（24 行）の書き換え
- [x] F05.1 §4 リクエスト/レスポンス仕様サブセクションの **見出しのみ** 書き換え
  （本文中の URL 引用は次 PR の TODO として残置）
- [x] F05.1 §4 冒頭スコープ移行注記の追記更新
- [x] F05.1 §4 reactions セクションに `GET /reactions/summary` を追記
- [x] F02.2.1 568 行の `bulletins/unread` を `dashboard/unread-threads` に書き換え
- [x] F09.8 §4 「コンテキスト取得」表に状態列追加 + 🔵 マーカ付与
- [x] `docs/internal/triage_log/bulletin.md` 新規作成（本ファイル）

## 7. 残課題（次 PR 候補）

- F05.1 §4 のリクエスト/レスポンス仕様サブセクション本文中の URL 引用書き換え
  （変更行数が膨大なため別 PR で実施）
- F05.1 §7.1 で言及されている `/api/v1/teams/{teamId}/announcements` の整合確認は
  announcement (F02.6 / F02.8) ドメイン triage に委任
- scanner V5 の `{_}/{_}` 二重スコープ展開でこぼれた `POST /api/v1/{_}/{_}/bulletin/threads`
  (F02.8 181 行) の準一致認識は scanner v6 課題として記録

---

## 8. 判断に迷った点

1. **`POST /threads/read-all` を 🔵 にするか削除するか**: 実装は無いが UX として価値の高い API。
   現状の DashboardController で全社横断の `/unread-threads` 取得 API はあるが、
   個別スコープ単位での read-all は未提供。設計書に「次フェーズで実装」と明記して 🔵 で残置。
2. **`PATCH /pin|lock|archive` を `POST` に書き換えるか、実装側を `PATCH` に揃えるか**:
   実装が `POST` で本番稼働中、フロントエンドも `POST` で叩いている前提のため、設計書を実装に
   合わせる方針が現実的（既存挙動を壊さない）。
3. **reactions API の scope 取扱**: 実装 `BulletinReactionController` のみ scope を取らない設計。
   target（thread or reply）の id だけで一意になるため URL を簡素化したと推察。設計書もこの
   方針に揃え、ただし bulletin ドメイン特有の例外として明記する。
4. **bulletin-threads/{id}/context を F05.1 と F09.8 のどちらで責任を持つか**:
   コルクボード（F09.8）の参照カード機能向けの補助 API であり、bulletin 機能本体（F05.1）の
   ユースケースではない。F09.8 が責任を持つ方針とし、F05.1 側に補足は加えない。
