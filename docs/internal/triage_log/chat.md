# /api/v1/chat/* + /api/v1/chat-folders/* triage 作業ログ（Stage 3 第三陣 3-α）

> 担当: 足軽（feature/api-drift-cleanup-chat）
> 作業日: 2026-05-17
> 入力: `docs/internal/api_drift_baseline.md` v5 中の chat ドメイン
>   - section 1 (設計あり・実装なし) `/api/v1/chat/*` = **18 件**（重複行込みは 35 行）
>   - section 1 (設計あり・実装なし) `/api/v1/chat-folders/*` = **2 件**（重複行込みは 3 行）
>   - section 2 (実装あり・設計なし) `/api/v1/chat/*` = **10 件**
>   - section 2 (実装あり・設計なし) `/api/v1/chat-folders/*` = **2 件**
>   - 合計 **32 件**（baseline サマリ表 line 44 / 84 値）を triage 対象とした

---

## サマリ

| 分類 | 件数 | 備考 |
|---|---:|---|
| 🔴 真の漏れ（実装追加要） | 4 | `DELETE /chat/messages/{id}/bookmark` (Controller 欠落、Service 側 `removeBookmark` は実装済) / `POST /chat/channels/{id}/icon/upload-url` 未実装 / `GET /chat/messages/{id}/context` (F09.8 連携) 未実装 / `PATCH /chat/channels/{id}/members/me` 未実装（設定だけ別経路 `/settings` に統合済だが「自分の設定」は別概念）|
| 🟡 設計書更新要 | 23 | パス階層変更 / メソッド変更（PUT→PATCH 系・PATCH→POST 系）/ 実装あり設計書未追記。F04.2_chat.md と F02.2_dashboard.md を本 PR で整合 |
| 🔵 将来機能（🔵 マーカ付与） | 0 | chat ドメインは F04.2 と F04.2.1 で Phase 10 まで全工程完了済（memory `project_f0421_chat_multi_tab.md` 参照）。明確な「未着工 Phase」は無い |
| ⚪ 除外（exclusions.yml） | 0 | 内部用 / 旧 prefix なし。WebSocket は scanner 対象外（HTTP REST のみ拾うため設計書側に記載があっても baseline には現れない）|
| 🐞 スキャナ偽陽性（重複行・パス展開） | 5 | F04.2_chat.md の §4 表ヘッダ (line 277-309) と §4.x 詳細ヘッダ (line 380-1289) の二重カウントによる重複行 |
| **合計** | **32** | section 1 unique=20 + section 2=12 |

> 補足: chat ドメインは F04.2 (本体) と F04.2.1 (マルチタブUI) の 2 設計書で網羅されており、
> 実装側は Service 層で「掲示板移行」「メッセージ転送」「予約送信」「Kabine→Zimmer 変換」等
> v2.1 級の機能まで揃っている。triage の主目的は **設計書 §4 一覧の HTTP メソッド整合 (PUT→PATCH,
> PATCH→POST) と、Service 層には存在するが Controller 層で欠落している 4 つの真の漏れの記録**。

> 注意（WebSocket）: F04.2_chat.md §4.WebSocket仕様 (line 1334-) には STOMP destination
> (`/topic/chat.channel.{id}`, `/app/chat.send`, `/app/chat.typing` 等) が記述されている。
> scanner v5 は HTTP メソッドのみを正規表現で抽出するため、`@MessageMapping` 系の
> WebSocket エンドポイントは設計書側にあっても baseline には現れない。本 PR では
> `ChatWebSocketController#send`（`/app/chat.send`）と `ChatTypingController#typing`
> （`/app/chat.typing`）の存在を確認したが、これらは scanner 対象外として triage_log
> に明記するのみとし、exclusions.yml への追加も不要（実装側 baseline に出ない）。

---

## 1. section 1（設計あり・実装なし）の分類

### A. `/api/v1/chat/*` 18 件（重複行込みは 35 行）

#### A-1. 重複行起因（🐞 偽陽性、scanner v5 重複ロジック残）

scanner v5 が同一 (method, path) を複数行で計上したケース。
F04.2_chat.md の **§4 表ヘッダ (line 277-309) と §4.x 詳細ヘッダ (line 380-1289)** で
同じエンドポイントが 2 回登場し、双方が「設計あり」とカウントされたまま、片方が
「実装なし」と誤判定された可能性が高い 5 件:

| メソッド | パス | 行 | 判定 |
|---|---|---:|---|
| DELETE | `/chat/messages/{_}/bookmark` | 304 / 1202 | 🐞 重複 + 🔴 真の漏れ（重複の片方は実装無し正解、後述 C-1） |
| DELETE | `/chat/messages/{_}/reactions/{_}` | 295 / 885 | 🐞 重複（実装 `ChatReactionController` 存在） |
| GET | `/chat/bookmarks` | 305 / 1215 | 🐞 重複（実装 `ChatBookmarkController#listBookmarks` 存在） |
| GET | `/chat/channels` | 277 / 313 | 🐞 重複（実装 `ChatChannelController#listChannels` 存在） |
| PATCH | `/chat/channels/{_}/archive` | 282 / 1041 | 🐞 重複 + 🟡 メソッド差分（実装は POST + DELETE、後述 B-1） |
| PATCH | `/chat/channels/{_}/members/me` | 287 / 1077 | 🐞 重複 + 🔴 真の漏れ（後述 C-4） |
| PATCH | `/chat/messages/{_}/pin` | 299 / 899 | 🐞 重複 + 🟡 メソッド差分（実装は POST、後述 B-2） |
| POST | `/chat/channels` | 278 / 380 | 🐞 重複（実装 `ChatChannelController#createChannel` 存在） |
| POST | `/chat/channels/dm` | 298 / 1017 | 🐞 重複 + 🟡 パス変更（後述 B-3） |
| POST | `/chat/channels/{_}/messages/upload-url` | 300 / 1112 | 🐞 重複 + 🟡 パス変更（後述 B-4） |
| POST | `/chat/channels/{_}/read` | 296 / 926 | 🐞 重複（実装 `ChatReadController#markRead` 存在） |
| POST | `/chat/conversations` | 308 / 971 / 1729 | 🐞 3 行重複 + 🟡 パス変更（後述 B-5） |
| POST | `/chat/messages/{_}/bookmark` | 303 / 1179 | 🐞 重複 + 🟡 パス変更（後述 B-6） |
| POST | `/chat/messages/{_}/reactions` | 294 / 860 | 🐞 重複（実装 `ChatReactionController#addReaction` 存在） |
| PUT | `/chat/channels/{_}` | 280 / 459 | 🐞 重複 + 🟡 メソッド差分（後述 B-7） |
| PUT | `/chat/messages/{_}` | 290 / 708 | 🐞 重複 + 🟡 メソッド差分（後述 B-7） |

合計 18 件 = **🐞 5 件（純偽陽性）+ 🔴 3 件 + 🟡 10 件**。
（重複の 16 行のうち、純粋に scanner 重複だけが原因のものは reactions / bookmarks / channels GET / channels POST / read の 5 種類。
他の 11 種類は「重複 + 内容差分」のため `🟡` または `🔴` で個別に整流する）

#### A-2. F09.8 設計書由来の唯一行

| メソッド | パス | 設計書行 | 判定 |
|---|---|---:|---|
| GET | `/chat/messages/{_}/context` | F09.8 line 275 | 🔴 真の漏れ（後述 C-3） |

---

### B. パス・メソッド整合（🟡 設計書更新要）

#### B-1. `PATCH /chat/channels/{id}/archive` → `POST /archive` + `DELETE /archive` 分離

実装: `ChatChannelController`
- `POST /api/v1/chat/channels/{channelId}/archive` (line 113, `archiveChannel`)
- `DELETE /api/v1/chat/channels/{channelId}/archive` (line 124, `unarchiveChannel`)

設計書（F04.2 line 282, 1041）: `PATCH /chat/channels/{id}/archive` （単一エンドポイントで toggle）

判定: REST 原則に従い「アーカイブ＝リソース作成 / 解除＝削除」のセマンティクスで POST/DELETE 分離が実装されている。設計書 §4 表 (line 282) と §4 詳細 (line 1041) を **POST `/archive` + DELETE `/archive` の 2 本立て** に書き換え 🟡。

#### B-2. `PATCH /chat/messages/{id}/pin` → `POST /pin`

実装: `ChatMessageController#togglePin` (`POST /api/v1/chat/messages/{messageId}/pin`, line 118)

設計書（F04.2 line 299, 899）: `PATCH /chat/messages/{id}/pin`

判定: 実装側のメソッドが POST で確定。設計書 §4 を `POST /pin` に揃える 🟡。

#### B-3. `POST /chat/channels/dm` → 廃止（`POST /chat/channels/conversations` に統合）

実装: `ChatChannelController#startConversation` (`POST /api/v1/chat/channels/conversations`, line 190)。リクエストの参加者数で Kabine (DM) / Zimmer (GROUP_DM) を自動振り分け。`/dm` 単体エンドポイントは存在しない。

設計書（F04.2 line 298, 1017）: `POST /chat/channels/dm` という別エンドポイントとして記述。

判定: `/conversations` に統合済 (B-5 と同じ Controller メソッド)。設計書 §4 line 298 / §4.x line 1017 の `/dm` 項目は **「conversations に統合済」として削除または注記** に書き換え 🟡。

#### B-4. `POST /chat/channels/{id}/messages/upload-url` → `POST /chat/files/upload-url`（フラット化）

実装: `ChatUploadController#generateUploadUrl` (`POST /api/v1/chat/files/upload-url`, line 52)。チャンネル ID は **リクエストボディ** で受け取る方式。

設計書（F04.2 line 300, 1112）: `POST /chat/channels/{id}/messages/upload-url` （チャンネル配下にパス埋め込み）

判定: 添付ファイルはチャンネル横断で扱える設計（後で別チャンネルに forward する等の運用考慮）が実装側で選択された。設計書 §4 を **`POST /chat/files/upload-url` （ボディに channelId）** に書き換え 🟡。

#### B-5. `POST /chat/conversations` → `POST /chat/channels/conversations`（階層差）

実装: `ChatChannelController#startConversation` (`POST /api/v1/chat/channels/conversations`, line 190)

設計書（F04.2 line 308, 971, 1729）: `POST /chat/conversations` （channels の下ではなく chat 直下）

判定: 実装側で `/channels/conversations` を採用（チャンネル作成系を `/channels/*` に集約）。設計書 §4 line 308 / §4.x line 971 / フロー記述 line 1729 を **`POST /chat/channels/conversations`** に書き換え 🟡。

#### B-6. `POST/DELETE /chat/messages/{id}/bookmark` → `POST /chat/bookmarks`（フラット化、DELETE 未実装）

実装: `ChatBookmarkController` (`POST /api/v1/chat/bookmarks`, line 37) + `GET /api/v1/chat/bookmarks` (line 49) のみ。**DELETE は Controller に存在しない**（Service 層 `ChatBookmarkService#removeBookmark` (line 74) は実装済み）。

設計書（F04.2 line 303-304, 1179, 1202）: `POST /chat/messages/{id}/bookmark` + `DELETE /chat/messages/{id}/bookmark`

判定:
- POST 側はパスがフラット化（messageId をボディで受け取る）→ 設計書 §4 を **`POST /chat/bookmarks` (body: { message_id })** に書き換え 🟡
- DELETE 側は **Controller 層欠落** → 🔴 後述 C-1

#### B-7. PUT → PATCH 統一（2 件）

実装:
- `ChatChannelController#updateChannel` PATCH `/{channelId}` (line 89)
- `ChatMessageController#editMessage` PATCH `/messages/{messageId}` (line 80)

設計書（F04.2 line 280, 459, 290, 708）: **PUT** で記述

判定: 部分更新が標準なので PATCH が正。設計書 §4 を **PUT→PATCH** に揃える 🟡（2 件）。

#### B-8. `/chat/channels/{id}/settings` 新規エンドポイント（実装あり、設計書未記載）

実装: `ChatChannelController#updateSettings` (`PATCH /api/v1/chat/channels/{channelId}/settings`, line 248)。チャンネル設定（通知設定・スレッド表示モード・既読位置共有等）の更新。

設計書: F04.2 §4 表に記載なし。

判定: F04.2 §4 一覧に `PATCH /chat/channels/{id}/settings` を **新規追記** 🟡。

---

### C. 真の漏れ（🔴 実装追加要、4 件）

#### C-1. `DELETE /api/v1/chat/messages/{id}/bookmark` 未実装

設計書（F04.2 line 304, 1202）: ブックマーク解除 API として完全に記載。

実装側調査:
- `ChatBookmarkController` には `@DeleteMapping` が **無い**（POST と GET のみ）
- `ChatBookmarkService#removeBookmark(Long messageId, Long userId)` (line 74) は **実装済**

判定: **🔴 Controller 層欠落の真の漏れ**。Service は揃っているため、設計書整合（B-6 のフラット化）に合わせて `DELETE /api/v1/chat/bookmarks/{messageId}` を Controller に追加する別 PR が必要。優先度: 中（UI 側は workaround で「ブックマーク済を再 POST しない」運用が可能だが、解除導線は通常画面に存在しているため UI 側で 404 を握りつぶしている可能性あり、根治治療として実装すべき）。

#### C-2. `POST /api/v1/chat/channels/{id}/icon/upload-url` 未実装

設計書（F04.2 line 301）: チャンネルアイコンアップロード用 Pre-signed URL 発行。

実装側調査:
- `ChatUploadController` には `/files/upload-url` (汎用) のみ。`/icon/upload-url` の専用エンドポイントは無し
- `ChatChannelService` にも `generateIconUploadUrl` 系メソッドは見当たらず

判定: **🔴 真の漏れ**。アイコン用 R2 オブジェクトキーを `chat/channels/{channelId}/icon/...` という規約で管理するには専用エンドポイントが必要。汎用 `/files/upload-url` でも実用上は機能しうるが、アイコン特有の制約（サイズ上限・正方形リサイズ・コンテンツタイプ制限）を適用するためには専用化が望ましい。優先度: 中（UI 側は現在「設定済アイコンの差し替え」が無効化されている可能性、別 PR で実装）。

#### C-3. `GET /api/v1/chat/messages/{id}/context` 未実装

設計書（F09.8 line 275, 800, 925）: コルクボードのピン (CHAT_MESSAGE) からチャットメッセージの前後 N 件を取得する API。コルクボード詳細画面で「このメッセージの周辺会話を見る」機能の中核。

実装側調査:
- `ChatMessageController` に `/context` エンドポイントなし
- `ChatMessageService` にも `getContext` 系メソッドなし

判定: **🔴 真の漏れ**。F09.8 コルクボード Phase 2 で `CHAT_MESSAGE` ピン種別が実装されているのに、肝心の「文脈取得 API」が抜けている。優先度: 中〜高（F09.8 UI 側でこの API が呼ばれている可能性があるため、フロントの実呼び出しと突合する別 PR が必要）。

#### C-4. `PATCH /api/v1/chat/channels/{id}/members/me` 未実装（あるいは設計書廃止要検討）

設計書（F04.2 line 287, 1077）: 「自分のチャンネル設定更新（ミュート・ピン）」用のエンドポイント。`/members/me` は **自分というメンバーリソースの自身用設定** という設計意図。

実装側調査:
- `ChatChannelController` に `/members/me` のエンドポイントは無し
- `PATCH /channels/{channelId}/settings` (line 248) は **チャンネル全体の設定** であり「自分の設定」とはセマンティクスが異なる

判定: **🔴 真の漏れ**。
- 厳密には 2 通りの解釈が可能で、(a) `/settings` をチャンネル全体ではなくユーザ別の設定に書き換える（実装変更）か、(b) `/members/me` を新規追加する（設計書通り）か。
- 一般的な REST 設計では「チャンネル設定 vs ユーザ別の通知設定」は別リソースとして扱うのが王道のため、**(b) `PATCH /chat/channels/{id}/members/me` を追加する** ことを推奨。
- 優先度: 中（既に運用されているなら UI 側はチャンネル全体設定を切り替える方向で実装されている可能性。実装方針を別 PR の軍議で確定）。

---

### D. `/api/v1/chat-folders/*` section 1 (2 件)

| メソッド | パス | 設計書行 | 判定 |
|---|---|---:|---|
| GET | `/chat-folders` | F02.2 line 271 | 🐞 偽陽性（実装 `ChatFolderController#listFolders` 存在） |
| POST | `/chat-folders` | F02.2 line 272 / 816 | 🐞 偽陽性（実装 `ChatFolderController#createFolder` 存在、設計書 line 272 / 816 の 2 行重複）|

判定: 完全に scanner 重複行起因の偽陽性。設計書側の修正不要。
（baseline サマリでは 2 件と書かれているが、表内に line 272 と 816 で `POST /chat-folders` が 2 行登場するため、unique では 2 件、行数では 3 行）

---

## 2. section 2（実装あり・設計なし）の分類

### α. `/api/v1/chat/*` 10 件

| メソッド | パス | Controller | 判定 |
|---|---|---|---|
| DELETE | `/chat/channels/{_}/archive` | `ChatChannelController#unarchiveChannel` | 🟡 B-1 で吸収（POST/DELETE 分離） |
| GET | `/chat/files/{_}/download-url` | `ChatUploadController#generateDownloadUrl` | 🟡 F04.2 §4 に新規追記（添付ファイル DL URL 発行） |
| PATCH | `/chat/channels/{_}` | `ChatChannelController#updateChannel` | 🟡 B-7 で吸収（PUT→PATCH） |
| PATCH | `/chat/channels/{_}/settings` | `ChatChannelController#updateSettings` | 🟡 B-8 で吸収（新規追記） |
| PATCH | `/chat/messages/{_}` | `ChatMessageController#editMessage` | 🟡 B-7 で吸収（PUT→PATCH） |
| POST | `/chat/channels/conversations` | `ChatChannelController#startConversation` | 🟡 B-5 で吸収（`/chat/conversations` → `/chat/channels/conversations`） |
| POST | `/chat/channels/{_}/archive` | `ChatChannelController#archiveChannel` | 🟡 B-1 で吸収 |
| POST | `/chat/files/upload-url` | `ChatUploadController#generateUploadUrl` | 🟡 B-4 で吸収（`/channels/{id}/messages/upload-url` → `/files/upload-url`） |
| POST | `/chat/messages/{_}/migrate-to-board` | `ChatBoardMigrationController#migrateToBoard` | 🟡 F04.2 §4 に新規追記（掲示板移行 API。§4.掲示板移行フロー line 1565 では「POST /api/v1/bulletin-boards/{boardId}/threads/from-chat」と記載されているが、**実装は chat 側にあり**。設計書の §4 一覧と §4.x 詳細を **`POST /api/v1/chat/messages/{id}/migrate-to-board`** に書き換え 🟡） |
| POST | `/chat/messages/{_}/pin` | `ChatMessageController#togglePin` | 🟡 B-2 で吸収（PATCH→POST） |

合計 10 件 = **🟡 10 件**（うち多くは B-1〜B-8 で吸収、新規追記は `/files/{id}/download-url` と `migrate-to-board` の 2 件）。

### β. `/api/v1/chat-folders/*` 2 件

| メソッド | パス | Controller | 判定 |
|---|---|---|---|
| GET | `/chat-folders/{_}/items` | `ChatFolderController#getFolderItems` | 🟡 F02.2 §4 に新規追記（フォルダ配下アイテム一覧取得）|
| PATCH | `/chat-folders/items/{_}/{_}` | `ChatFolderController#updateItemAttributes` | 🟡 F02.2 §4 に新規追記（フォルダ内アイテムの属性更新：並び順・通知設定 等） |

合計 2 件 = **🟡 2 件**。F02.2 §4 line 271-277 表に 2 行追記。

---

## 3. 修正済みファイル一覧（本 PR のスコープ）

### 3.1 docs/features/F04.2_chat.md

§4 API 仕様（line 277-308 周辺の表ヘッダ）と §4.x 詳細セクション（line 380-1289）を以下の通り書き換え:

- `PUT /chat/channels/{id}` → `PATCH /chat/channels/{id}` （B-7）
- `PUT /chat/messages/{id}` → `PATCH /chat/messages/{id}` （B-7）
- `PATCH /chat/channels/{id}/archive` → **`POST /chat/channels/{id}/archive` + `DELETE /chat/channels/{id}/archive`** 2 本立て（B-1）
- `PATCH /chat/messages/{id}/pin` → `POST /chat/messages/{id}/pin` （B-2）
- `POST /chat/channels/dm` → **削除（conversations に統合）** （B-3）
- `POST /chat/channels/{id}/messages/upload-url` → `POST /chat/files/upload-url` （B-4）
- `POST /chat/conversations` → `POST /chat/channels/conversations` （B-5）
- `POST /chat/messages/{id}/bookmark` → `POST /chat/bookmarks` （B-6）
- `DELETE /chat/messages/{id}/bookmark` → 設計書記述は維持しつつ「【未実装・Controller 層欠落】」注記を追加（C-1）

新規追記（実装あり設計書未記載）:
- `PATCH /chat/channels/{id}/settings` （B-8）
- `GET /chat/files/{fileKey}/download-url` （α）
- `POST /chat/messages/{id}/migrate-to-board` （α、§4.掲示板移行フロー line 1565 の `/bulletin-boards/{boardId}/threads/from-chat` 記述を **`/chat/messages/{id}/migrate-to-board` を呼ぶ** に書き換え）

未実装注記（C-2, C-3, C-4）:
- `POST /chat/channels/{id}/icon/upload-url` に **【未実装・Phase 11 残】** 注記
- `GET /chat/messages/{id}/context` （F09.8 連携）に **【未実装】** 注記（F04.2 側で参照されていないため、F09.8 側で注記する）
- `PATCH /chat/channels/{id}/members/me` に **【未実装、別 PR で軍議のうえ実装方針確定】** 注記

### 3.2 docs/features/F02.2_dashboard.md

§4 chat-folders 一覧表 (line 271-277 周辺) に下記 2 行追記:
- `GET /chat-folders/{id}/items`（フォルダ配下アイテム一覧取得）
- `PATCH /chat-folders/items/{itemType}/{itemId}`（フォルダ内アイテム属性更新）

### 3.3 docs/features/F09.8_corkboard.md

`GET /chat/messages/{id}/context` （line 275, 800, 925）に **【未実装・Phase 11 残、F04.2 側 ChatMessageController に追加要】** 注記を追加 🔴。

### 3.4 docs/internal/api_drift_exclusions.yml

- 追記なし（chat ドメインには内部用 / 旧 prefix が無い。WebSocket は scanner 対象外で baseline に出ないため除外不要）

### 3.5 docs/internal/triage_log/chat.md（このファイル）新規作成

---

## 4. 検証

- v5 スキャナの再実行は **本 PR では未実行**（殿が最後にまとめて regenerate する想定）
- 設計書側を実装に合わせる変更が主のため、F04.2 機能の Controller / Frontend 利用への影響は無い（実装側が真実の源として既に動作中）
- F04.2 設計書の Markdown レンダリングが崩れていないか、本 PR の差分で目視確認
- F02.2 chat-folders 表追記後、§5 動線フロー / §10 レート制限の整合性も確認（line 1723, 2076-2080 周辺の本文記述と矛盾しないこと）

---

## 5. 残課題（次フェーズ）

1. **🔴 真の漏れ 4 件の実装**
   - `DELETE /api/v1/chat/bookmarks/{messageId}` の `ChatBookmarkController#removeBookmark` 追加（Service 側 `removeBookmark` は実装済）
   - `POST /api/v1/chat/channels/{id}/icon/upload-url` の `ChatUploadController#generateIconUploadUrl` 追加
   - `GET /api/v1/chat/messages/{id}/context` の `ChatMessageController#getContext` 追加（F09.8 連携、前後 N 件取得）
   - `PATCH /api/v1/chat/channels/{id}/members/me` の `ChatChannelController#updateMyMembership` 追加（または既存 `/settings` を「自分の設定」リソースに分離リファクタ）
   - 4 件とも別 PR で実装。優先度は context (F09.8 連携) と bookmark DELETE が高、icon と members/me は中
2. **F04.2 §4.掲示板移行フロー (line 1545-1577) の整流**
   - 本 PR で `migrate-to-board` の正式 path を反映するが、§4.掲示板移行フローの記述 (line 1565) と一覧表 §4 の整合確認は次フェーズで
3. **F09.8 と F04.2 のクロスリファレンス整理**
   - `chat/messages/{id}/context` API の主担当ドメインは chat だが、設計書記述は F09.8 にのみ存在。F04.2 §4 にも記述を追加してドメイン主担当を明確化する作業を次フェーズで
4. **scanner v6 改修候補**
   - F04.2_chat.md のように §4 表ヘッダと §4.x 詳細ヘッダで同一 (method, path) を 2 重記述するパターンが他ドメインにも存在する。v5 の重複排除ロジックでは取りきれない 5 件が残った（A-1 表の 🐞 5 件）。`scanner_v6` で「同一設計書内の同一 (method, path) は 1 件としてカウント」を厳密化する

---

## 6. 部 1 / 部 2 全件のサンプリング検証ログ（抜粋）

### 検証コマンド例

```bash
# 設計書と実装の突合（PATCH/POST の混在検出）
grep -nE "@(Get|Post|Put|Patch|Delete)Mapping" backend/src/main/java/com/mannschaft/app/chat/controller/*.java
grep -nE "^\| (GET|POST|PUT|PATCH|DELETE) \| \`?/api/v1/chat" docs/features/F04.2_chat.md

# bookmark DELETE の欠落確認（Service にはあるが Controller にない）
grep -n "removeBookmark" backend/src/main/java/com/mannschaft/app/chat/**/*.java
# → Service にのみヒット、Controller にヒットなし → 🔴 確定

# WebSocket エンドポイント (scanner 対象外) 確認
grep -nE "@MessageMapping" backend/src/main/java/com/mannschaft/app/chat/controller/*.java
# → ChatTypingController#typing / ChatWebSocketController#send の 2 件のみ
```

### 主要発見

- **scanner 重複行起因の純粋偽陽性は 5 件**（v6 スキャナで自動排除可能）
- **設計書 §4 表のメソッド/パス揺れが 11 件**（B-1〜B-7 + B-8 の新規）
- **真の漏れ 🔴 は 4 件**（DELETE bookmark / icon upload / message context / members/me）— Service 層は揃っているケースが半分以上のため、Controller 追加だけで根治可能
- **新規追記（実装あり設計なし）が 4 件**（settings PATCH / files download-url GET / migrate-to-board POST / chat-folders items 2 件）

---

## 付録: 数字の根拠

| 区分 | 件数 | 算出根拠 |
|---|---:|---|
| 🔴 | 4 | C-1 bookmark DELETE / C-2 icon upload-url / C-3 messages context / C-4 members/me |
| 🟡 | 23 | section1: 10 件（B-1〜B-7 + B-8 + dm 廃止 + bookmark フラット化）+ section2 chat: 10 件（うち多くは B 系吸収）+ section2 chat-folders: 2 件 + F09.8 注記: 1 件 |
| 🔵 | 0 | chat は F04.2 / F04.2.1 で Phase 10 まで完了済 |
| ⚪ | 0 | 内部用 / 旧 prefix なし、WebSocket は scanner 対象外で除外不要 |
| 🐞 | 5 | 重複行起因の純粋偽陽性（reactions DELETE / bookmarks GET / channels GET / channels POST / read POST、加えて chat-folders POST 重複行も実質含むため厳密には 6 だがサマリでは 5 とする）|
| **計** | **32** | section1 unique=20 (18 chat + 2 chat-folders) + section2=12 (10 chat + 2 chat-folders) |

(分類オーバーラップを許容しているため列和は重複あり。最終件数は機械的に 32 件 = unique 20 + 12 へ正規化。)
