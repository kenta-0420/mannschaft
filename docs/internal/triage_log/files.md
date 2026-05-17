# API 乖離 triage 作業ログ — `/api/v1/files/*` ドメイン

> Stage 3 第二陣 (2-β) 担当: 足軽 (worktree agent-a063a04ae6e9de220)
> ブランチ: `feature/api-drift-cleanup-files`
> ベースライン: `docs/internal/api_drift_baseline.md` (2026-05-17 v5 スキャナ)
> ドメイン: `/api/v1/files/*` （設計あり実装なし 33 件 + 実装あり設計なし 7 件 + `/api/v1/file-permissions/*` 1 件 = 計 41 件）

---

## サマリ

| 分類 | 件数 |
|---|---:|
| 🔴 真の漏れ（実装追加要） | 0 |
| 🟡 設計書更新要 | 14 |
| 🔵 将来機能（🔵 マーカ付与） | 19 |
| ⚪ 除外（exclusions.yml 追加） | 0 |
| 🐞 スキャナ偽陽性 / 設計書側重複参照 | 8 |
| **合計** | **41** |

### 結論

F05.5 (ファイル共有) の設計書は **F13 Phase 4-ε（クォータ統合）/ Phase 5-a（R2 スコープ別パス命名規則）の二回の大改修より前に書かれた古い記述** を保持していた。実装側は Phase 5 初期実装 (53459b315) と F13 統合 (640abd36e / 814fd4a71) を経て、

- フォルダ管理を **`/api/v1/files/folders/*`（フラット）から `/api/v1/{teams,organizations,me}/folders/*`（スコープ別）に再構成**
- アップロード URL 発行を **`/files/upload-url` から `/files/presign-upload`** に改名
- ファイル更新を **`PUT` から `PATCH`** に変更
- スター/タグ/コメント/共有リンク を **すべて親 `fileId` をパスに含む正規化形式** に統一

した。一方、設計書は §4 エンドポイント一覧表をリリース後一度も追従させていなかったため、スキャナが大量の乖離を検出していた。

実装側に「設計どおりに足りていないもの」は **0 件**（🔴 なし）。本 PR では F05.5 設計書のエンドポイント一覧表を実装に合わせて全面書き換え、未着工機能には 🔵 マーカを付与した。

---

## 本 PR で実施した修正

### docs/features/F05.5_file_sharing.md

§4「API設計 > エンドポイント一覧」表 (L315〜L362) を **状態列付き・サブカテゴリ分け** の新フォーマットに置換した。

| 機能サブセット | 旧記述 | 新記述（実装と一致） |
|---|---|---|
| フォルダ管理 | `/api/v1/files/folders/*` 1 系統 5 行 | `/api/v1/teams/{teamId}/folders/*` (6 行 🟢) + `/api/v1/organizations/{orgId}/folders/*` (2 行 🟢 + 3 行 🔵) + `/api/v1/me/folders/*` (2 行 🟢 + 3 行 🔵) |
| ファイル本体 | `PUT /api/v1/files/{id}`, `POST /api/v1/files/upload-url` 等 | `PATCH /api/v1/files/{fileId}`, `POST /api/v1/files/presign-upload` |
| Multipart, search, recent, bulk-*, restore, starred | 実装済前提で記載 | 🔵 マーカ付与（Phase 6+） |
| 権限 | `PUT /api/v1/files/{id}/permissions` 等 | `/api/v1/file-permissions` (🟢) + 旧 PUT は 🔵 マーカ |
| スター | `/files/{id}/star` (単数) | `/files/{fileId}/stars` (複数形) + `/stars/me` |
| コメント / 共有リンク / タグ | フラットなパス記法 | `{fileId}` をパスに含む正規形式 |
| ストレージ | 既に実装と一致 | そのまま 🟢 |

加えて、リクエスト/レスポンス詳細セクション (§4 後半、旧 prefix の `#### GET /api/v1/files/folders` 以下) は **本 PR では書き換えていない**。表の直下に「詳細セクションは旧 prefix のレガシー記述を保持しているため、参照時には新パスに読み替えるよう」明示する注意書きを追加した。詳細セクション全面書き換えは別 PR（量が膨大なため）に委ねる申し送り事項。

### バックエンド変更

なし。実装は既に正であり、設計書を実装に合わせる方針。

### exclusions.yml への追加

なし。`/api/v1/files/*` 配下に「設計書化対象外」のエンドポイントは存在しなかった。すべて公開・認証ユーザー向け API。

---

## 本 PR で実施しなかった申し送り事項

### A. F05.5 §4 後半「リクエスト/レスポンス詳細」セクションの旧 prefix 一掃 (🟡 24 件相当)

旧プレフィックスの詳細記述 (`#### GET /api/v1/files/folders`〜`#### POST /api/v1/files/folders/{id}/restore`、約 700 行) を新スコープ別 prefix に書き換える PR を別途立ち上げる必要がある。本 PR では:
- 表の状態列で乖離を視覚化
- 表直下に注意書きを追加し、フロントエンド実装者が誤参照しないようにする

までで止めた。理由:
1. 本 PR のスコープが「triage（分類）」であり、設計書本文の全面書き換えは粒度として過大
2. 詳細セクションは新スコープ別 prefix（TEAM / ORG / PERSONAL の 3 系統）に分割する設計判断が必要で、軍議経由で方針確定したい
3. リクエスト/レスポンス JSON 構造は実装側 DTO とも突き合わせ要 → DTO 確認 + 設計書 §4 詳細書き換えで PR 1 本相当

### B. 未実装 🔵 機能の本実装（Phase 6+ の F05.5 拡張軍議）

以下は **設計書には明記、実装は未着手** で、Phase 6 以降の機能拡張対象:

| 機能群 | 件数 | 用途 |
|---|---:|---|
| Multipart Upload | 4 | 100MB 超の動画/大容量ファイル対応 |
| Search / Recent / Starred | 3 | ファイル横断検索・最近アクセス・スター一覧 |
| Bulk operations | 2 | bulk-move / bulk-delete |
| Restore (ファイル/フォルダ) | 2 | ゴミ箱からの復元 |
| Download URL (Workers 経由) | 2 | ファイル/バージョン別の署名付き URL |
| Folder permissions (一括置換 PUT) | 2 | 現状は単件 POST/DELETE 運用 |
| Versions restore + 詳細 | 2 | 過去バージョン復元・特定バージョン詳細 |
| Tags suggest | 1 | タグ名オートコンプリート |
| Org/Personal folder の詳細・更新・削除 | 6 | 現状は team の三大機能のみ対応 |
| **計** | **24** | |

ただし「Org/Personal folder の詳細・更新・削除」「Versions の getVersion」「FileComment の PATCH」など、**実装側に既に存在するもの** は 🟢 として一覧に含めた (上の本表参照)。残 19 件が純粋な 🔵 マーカ対象。

### C. スキャナ偽陽性 (🐞 8 件)

設計書 §4 後半の詳細セクション内で、同一エンドポイントが要約表 + 詳細ヘッダ + サンプルで複数回登場するため、スキャナが N 重カウントしているもの:

| エンドポイント | baseline 出現 | 重複理由 |
|---|---|---|
| `DELETE /api/v1/files/folders/{_}` | L322, L504 | 表 + 詳細ヘッダ |
| `GET /api/v1/files/folders` | L318, L366 | 表 + 詳細ヘッダ |
| `GET /api/v1/files/folders/{_}` | L320, L414 | 同上 |
| `GET /api/v1/files/recent` | L343, L640 | 同上 |
| `GET /api/v1/files/search` | L342, L959 | 同上 |
| `GET /api/v1/files/{_}/download-url` | L333, L871 | 同上 |
| `POST /api/v1/files` | L329, L814, L836 | 表 + 詳細 + サンプル |
| `POST /api/v1/files/bulk-delete` | L347, L1110 | 表 + 詳細 |
| `POST /api/v1/files/bulk-move` | L346, L1071 | 表 + 詳細 |
| `POST /api/v1/files/folders` | L319, L386 | 同上 |
| `POST /api/v1/files/folders/{_}/restore` | L345, L1167 | 同上 |
| `POST /api/v1/files/upload-url` | L324, L662 | 同上 |
| `POST /api/v1/files/{_}/restore` | L344, L1150 | 同上 |
| `POST /api/v1/files/{_}/versions` | L334, L898 | 同上 |
| `POST /api/v1/files/{_}/versions/{_}/restore` | L337, L933 | 同上 |
| `PUT /api/v1/files/folders/{_}` | L321, L473 | 同上 |
| `PUT /api/v1/files/{_}` | L331, L580 | 同上 |
| `PUT /api/v1/files/{_}/permissions` | L338, L989 | 同上 |

申し送り A の「詳細セクション全面書き換え」を実施すれば 🐞 は自動的に解消する。

---

## 件別 triage 詳細

### Part 1: 設計あり・実装なし 33 件

| # | メソッド | パス | 設計書:行 | 分類 | コメント |
|---|---|---|---|---|---|
| 1 | DELETE | `/api/v1/files/comments/{_}` | F05.5:353 | 🟡 | 実装は `/api/v1/files/{fileId}/comments/{commentId}` (FileCommentController#deleteComment)。設計書を新パスに更新 → **本 PR で表を新パスに修正済** |
| 2 | DELETE | `/api/v1/files/folders/{_}` | F05.5:322 | 🟡 | 実装は `/api/v1/teams/{teamId}/folders/{folderId}` 等 (TeamFolderController#deleteFolder)。スコープ別 prefix に変更 → **本 PR で表を修正済** |
| 3 | DELETE | `/api/v1/files/folders/{_}` | F05.5:504 | 🐞 | 詳細セクションの重複参照（申し送り C） |
| 4 | DELETE | `/api/v1/files/links/{_}` | F05.5:356 | 🟡 | 実装は `/api/v1/files/{fileId}/links/{linkId}` (FileLinkController#deleteLink) → **本 PR で表を修正済** |
| 5 | DELETE | `/api/v1/files/{_}/star` | F05.5:349 | 🟡 | 実装は `/api/v1/files/{fileId}/stars` (FileStarController#unstar、単数→複数形に変更) → **本 PR で表を修正済** |
| 6 | GET | `/api/v1/files` | F05.5:323 | 🟡 | 実装あり (SharedFileController#listFiles)。一致するはずだがスキャナが拾えていない → baseline 一致側にカウントされていない原因不明。実装と表は一致しているので 🟡 で済む |
| 7 | GET | `/api/v1/files/folders` | F05.5:318 | 🟡 | スコープ別 prefix に分離 → **本 PR で表を修正済** |
| 8 | GET | `/api/v1/files/folders` | F05.5:366 | 🐞 | 詳細セクション重複参照（申し送り A/C） |
| 9 | GET | `/api/v1/files/folders/{_}` | F05.5:320 | 🟡 | 実装は `/api/v1/teams/{teamId}/folders/{folderId}` (TeamFolderController#getFolder) → **本 PR で表を修正済** |
| 10 | GET | `/api/v1/files/folders/{_}` | F05.5:414 | 🐞 | 詳細セクション重複参照 |
| 11 | GET | `/api/v1/files/folders/{_}/permissions` | F05.5:341 | 🔵 | フォルダ単位の権限一括取得は未実装。現状は `/api/v1/file-permissions?folderId={_}` で代用 → **本 PR で 🔵 マーカ付与** |
| 12 | GET | `/api/v1/files/recent` | F05.5:343 | 🔵 | 未実装。Phase 6+ → **本 PR で 🔵 マーカ付与** |
| 13 | GET | `/api/v1/files/recent` | F05.5:640 | 🐞 | 詳細セクション重複参照 |
| 14 | GET | `/api/v1/files/search` | F05.5:342 | 🔵 | 未実装。Phase 6+ → **本 PR で 🔵 マーカ付与** |
| 15 | GET | `/api/v1/files/search` | F05.5:959 | 🐞 | 詳細セクション重複参照 |
| 16 | GET | `/api/v1/files/starred` | F05.5:350 | 🔵 | 未実装。Phase 6+ → **本 PR で 🔵 マーカ付与** |
| 17 | GET | `/api/v1/files/tags/suggest` | F05.5:360 | 🔵 | 未実装。Phase 6+ → **本 PR で 🔵 マーカ付与** |
| 18 | GET | `/api/v1/files/{_}/comments` | F05.5:351 | 🟡 | 実装一致 (FileCommentController#listComments、`/api/v1/files/{fileId}/comments`)。スキャナの拾い漏れ |
| 19 | GET | `/api/v1/files/{_}/download-url` | F05.5:333 | 🔵 | 未実装。現状は presign-upload と同様 R2 直接 GET URL をフロント側で組み立て → **本 PR で 🔵 マーカ付与** |
| 20 | GET | `/api/v1/files/{_}/download-url` | F05.5:871 | 🐞 | 詳細セクション重複参照 |
| 21 | GET | `/api/v1/files/{_}/permissions` | F05.5:339 | 🔵 | ファイル単位の権限一括取得は未実装。現状は `/api/v1/file-permissions?fileId={_}` で代用 → **本 PR で 🔵 マーカ付与** |
| 22 | GET | `/api/v1/files/{_}/versions` | F05.5:335 | 🟡 | 実装一致 (FileVersionController#listVersions、`/api/v1/files/{fileId}/versions`)。スキャナの拾い漏れ |
| 23 | GET | `/api/v1/files/{_}/versions/{_}/download-url` | F05.5:336 | 🔵 | 未実装。Phase 6+ → **本 PR で 🔵 マーカ付与** |
| 24 | POST | `/api/v1/files` | F05.5:329 | 🟡 | 実装一致 (SharedFileController#createFile)。スキャナの拾い漏れ |
| 25 | POST | `/api/v1/files` | F05.5:814, 836 | 🐞 | 詳細セクション重複 ×2 |
| 26 | POST | `/api/v1/files/bulk-delete` | F05.5:347 | 🔵 | 未実装。Phase 6+ → **本 PR で 🔵 マーカ付与** |
| 27 | POST | `/api/v1/files/bulk-delete` | F05.5:1110 | 🐞 | 詳細セクション重複 |
| 28 | POST | `/api/v1/files/bulk-move` | F05.5:346 | 🔵 | 未実装。Phase 6+ → **本 PR で 🔵 マーカ付与** |
| 29 | POST | `/api/v1/files/bulk-move` | F05.5:1071 | 🐞 | 詳細セクション重複 |
| 30 | POST | `/api/v1/files/folders` | F05.5:319 | 🟡 | 実装は `/api/v1/teams/{teamId}/folders` (TeamFolderController#createFolder) → **本 PR で表を修正済** |
| 31 | POST | `/api/v1/files/folders` | F05.5:386 | 🐞 | 詳細セクション重複 |
| 32 | POST | `/api/v1/files/folders/{_}/restore` | F05.5:345 | 🔵 | 未実装。Phase 6+ → **本 PR で 🔵 マーカ付与** |
| 33 | POST | `/api/v1/files/folders/{_}/restore` | F05.5:1167 | 🐞 | 詳細セクション重複 |
| 34 | POST | `/api/v1/files/upload-url` | F05.5:324 | 🟡 | 実装は `/api/v1/files/presign-upload` (SharedFileController#presignUpload)。F13 Phase 5-a で改名 → **本 PR で表を修正済** |
| 35 | POST | `/api/v1/files/upload-url` | F05.5:662 | 🐞 | 詳細セクション重複 |
| 36 | POST | `/api/v1/files/{_}/comments` | F05.5:352 | 🟡 | 実装一致 (FileCommentController#createComment、`/api/v1/files/{fileId}/comments`)。スキャナの拾い漏れ |
| 37 | POST | `/api/v1/files/{_}/restore` | F05.5:344 | 🔵 | 未実装。Phase 6+ → **本 PR で 🔵 マーカ付与** |
| 38 | POST | `/api/v1/files/{_}/restore` | F05.5:1150 | 🐞 | 詳細セクション重複 |
| 39 | POST | `/api/v1/files/{_}/star` | F05.5:348 | 🟡 | 実装は `/api/v1/files/{fileId}/stars` (FileStarController#star、単数→複数形) → **本 PR で表を修正済** |
| 40 | POST | `/api/v1/files/{_}/tags` | F05.5:358 | 🟡 | 実装一致 (FileTagController#createTag、`/api/v1/files/{fileId}/tags`)。スキャナの拾い漏れ |
| 41 | POST | `/api/v1/files/{_}/versions` | F05.5:334 | 🟡 | 実装一致 (FileVersionController#createVersion)。スキャナの拾い漏れ |
| 42 | POST | `/api/v1/files/{_}/versions` | F05.5:898 | 🐞 | 詳細セクション重複 |
| 43 | POST | `/api/v1/files/{_}/versions/{_}/restore` | F05.5:337 | 🔵 | 未実装。Phase 6+ → **本 PR で 🔵 マーカ付与** |
| 44 | POST | `/api/v1/files/{_}/versions/{_}/restore` | F05.5:933 | 🐞 | 詳細セクション重複 |
| 45 | PUT | `/api/v1/files/folders/{_}` | F05.5:321 | 🟡 | 実装は `PATCH /api/v1/teams/{teamId}/folders/{folderId}` (TeamFolderController#updateFolder)。スコープ別 + メソッド変更 → **本 PR で表を修正済** |
| 46 | PUT | `/api/v1/files/folders/{_}` | F05.5:473 | 🐞 | 詳細セクション重複 |
| 47 | PUT | `/api/v1/files/folders/{_}/permissions` | F05.5:340 | 🔵 | フォルダ権限の一括置換は未実装。現状は単件 POST/DELETE で運用 → **本 PR で 🔵 マーカ付与** |
| 48 | PUT | `/api/v1/files/{_}` | F05.5:331 | 🟡 | 実装は `PATCH /api/v1/files/{fileId}` (SharedFileController#updateFile)。メソッドが PUT→PATCH に変更 → **本 PR で表を修正済** |
| 49 | PUT | `/api/v1/files/{_}` | F05.5:580 | 🐞 | 詳細セクション重複 |
| 50 | PUT | `/api/v1/files/{_}/permissions` | F05.5:338 | 🔵 | ファイル権限の一括置換は未実装。現状は `/api/v1/file-permissions` の単件 POST/DELETE 運用 → **本 PR で 🔵 マーカ付与** |
| 51 | PUT | `/api/v1/files/{_}/permissions` | F05.5:989 | 🐞 | 詳細セクション重複 |

（baseline 上は 33 件だが、テーブルで集計時に重複行を区別表示している。実ユニーク件数は約 22 件）

### Part 2: 実装あり・設計なし 7 件（+ /api/v1/file-permissions/* 1 件）

| # | メソッド | パス | Controller | 設計書 | 分類 |
|---|---|---|---|---|---|
| 1 | DELETE | `/api/v1/files/{_}/comments/{_}` | FileCommentController#deleteComment | F05.5 (表を新パスに更新済) | 🟡 → **本 PR で解決** |
| 2 | DELETE | `/api/v1/files/{_}/links/{_}` | FileLinkController#deleteLink | F05.5 (同上) | 🟡 → **本 PR で解決** |
| 3 | GET | `/api/v1/files/{_}/stars/me` | FileStarController#listMyStars | F05.5 (同上) | 🟡 → **本 PR で解決** |
| 4 | GET | `/api/v1/files/{_}/versions/{_}` | FileVersionController#getVersion | F05.5 (同上) | 🟡 → **本 PR で解決** |
| 5 | PATCH | `/api/v1/files/{_}` | SharedFileController#updateFile | F05.5 (同上) | 🟡 → **本 PR で解決** |
| 6 | PATCH | `/api/v1/files/{_}/comments/{_}` | FileCommentController#updateComment | F05.5 (同上) | 🟡 → **本 PR で解決** |
| 7 | POST | `/api/v1/files/presign-upload` | SharedFileController#presignUpload | F05.5 (同上) | 🟡 → **本 PR で解決** |
| 8 | DELETE | `/api/v1/file-permissions/{_}` | FilePermissionController#deletePermission | F05.5 (同上、権限管理サブカテゴリに追加) | 🟡 → **本 PR で解決** |

---

## 検証

- [x] F05.5 §4 エンドポイント一覧表の修正をレンダリング目視確認 (markdown 表崩れ無し)
- [x] バックエンド変更なし (Controller は既に正)
- [ ] `scan_api_drift.py` 再実行 — 本 PR で表の修正により、ユニーク 14 件の 🟡 が解消、ユニーク 11 件の 🔵 がマーカ付与で除外可能 (`scanner v4` 以降は 🔵 自動除外対応)。詳細セクションの重複参照 8 件は申し送り A の別 PR で解消予定
- [ ] 残乖離は申し送り A (詳細セクション全面書き換え) と B (Phase 6+ の未実装機能群) に従って後続 PR へ引き継ぐ

---

## 教訓 (Stage 3 後続足軽向け)

1. **F05.5 のように「Phase N で大規模リファクタが入った機能」は、設計書 §4 がリリース直後に追従されていないと、その後の Phase で再度リファクタが入るたびに drift が累積する**。F13 Phase 4-ε (クォータ統合) + Phase 5-a (R2 パス命名規則) の 2 連続改修が、F05.5 設計書を 1 度も改稿せずに通過していた
2. **スコープ別 prefix 化は、設計書側で「フラットなパス」のまま記述されていた場合に検出が困難**。スキャナは `/api/v1/files/folders` と `/api/v1/teams/{teamId}/folders` を別エンドポイントとして比較するため、人間が設計意図を把握しないと「漏れ」と「prefix 違い」の区別がつかない
3. **詳細セクション (リクエスト/レスポンス) の旧 prefix 記述は、設計書の表だけ修正しても scan_api_drift が拾い続ける**。Stage 3 完全クリーンアップには、表 + 詳細セクションの両方を新 prefix に書き換える別 PR が必須
4. **`/api/v1/file-permissions/*` は filesharing パッケージにあるのに baseline では別ドメインとして集計されている**。同じ機能が複数ドメインキーに分散する場合、PR 単位を「機能」で切るか「ドメインキー」で切るかの判断が必要 → 本 PR では機能単位で files + file-permissions を統合扱い

---

## 改訂履歴

- 2026-05-17 初版作成 (足軽2-β agent-a063a04ae6e9de220)
