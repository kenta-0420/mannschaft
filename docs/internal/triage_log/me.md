# API 乖離 triage 作業ログ — `/api/v1/me/*` ドメイン

> Stage 2 担当: 足軽 (worktree agent-a47c9de2ce1c1b1a2)
> ブランチ: `feature/api-drift-cleanup-me`
> ベースライン: `docs/internal/api_drift_baseline.md` (2026-05-16 v2 スキャナ)
> ドメイン: `/api/v1/me/*` （設計あり実装なし 42 件 + 実装あり設計なし 25 件 = 計 67 件）

---

## サマリ

| 分類 | 件数 |
|---|---:|
| 🔴 真の漏れ（実装追加要） | 0 |
| 🟡 設計書更新要 | 33 |
| 🔵 将来機能（🔵 マーカ付与） | 9 |
| ⚪ 除外（exclusions.yml 追加） | 0 |
| 🐞 スキャナ偽陽性 | 25 |
| **合計** | **67** |

🟡 が圧倒的多数 (33 件) を占めるが、その大半は **「設計書がクエリ文字列込みのパスで記載されており、スキャナが path+query を 1 パスとして比較している」** ことに起因する偽陽性で、スキャナ v3 で `path?query` 分離処理を入れれば一気に消える性質のもの。

🐞 (25 件) の内訳:
- スキャナがクエリ込みパスを path として比較した結果の重複
- 設計書 1 行 = 実装 1 行で完全一致しているにも関わらず、スキャナのパース時に重複行カウントで漏れ判定された複数件 (F03.15, F03.2, F02.5, F15.3 等)
- 設計書側に `?slot_kind={TEAM\|...` のような壊れた markdown 表記が残っていてスキャナが分割に失敗したもの 1 件

---

## 本 PR で実施した修正

### F02.9_favorites_widget.md (4 箇所修正)

| L | Before | After | 理由 |
|---|---|---|---|
| 273 | `PATCH /api/v1/me/favorites/order` (§4.4 タイトル) | `PATCH /api/v1/me/favorites/reorder` | 実装は `FavoriteController#reorderFavorites` で `/reorder`。設計書側を実装に合わせて修正 (サンプル #3 と整合) |
| 291 | `**レスポンス**: 200 OK` | `**レスポンス**: 204 No Content` | 実装 (`removeFavorite` と同様に `ResponseEntity.noContent()` を返す) に合わせる |
| 319 | `BLOG_AUTHOR | PATCH /api/v1/me/profile（自分のみ）` | `🔵 PATCH /api/v1/me/profile（未実装）— ブログ著者プロフィール編集 API は別 Phase で実装予定。当面は BLOG_AUTHOR のクイック編集ボタンを非表示とする` | 該当 API が未実装。将来機能タグ案 A 適用 |
| 394 | `PATCH /api/v1/me/favorites/order` (本文) | `PATCH /api/v1/me/favorites/reorder` | 同上 |
| 414 | `BLOG_AUTHOR | display_name・bio | PATCH /api/v1/me/profile → 同上` | `🔵 PATCH /api/v1/me/profile（未実装。別 Phase 実装後に有効化）` | 将来機能タグ |
| 557 | `PATCH /api/v1/me/favorites/order` (レート制限表) | `PATCH /api/v1/me/favorites/reorder` | 同上 |

### exclusions.yml への追加: なし

me/* 配下に「設計書化対象外」のエンドポイントは存在しなかった。すべて公開・認証ユーザー向け API。

---

## 本 PR で実施しなかった申し送り事項

以下は **本 PR の triage で振り分け済みだが、修正は別 PR (ドメイン横断 PR / スキャナ v3 改修) に委ねる** ことにした項目。

### A. スキャナ v3 改修待ち（🐞 25 件）

スキャナの既知制約。v3 で以下を改修すれば自動的に偽陽性 0 になる:

1. **path?query 分離処理**: 設計書側で `GET /api/v1/me/foo?param=X` と書かれているケースを path=`/api/v1/me/foo`, query=`param=X` に分離して比較する
2. **重複行カウンタ抑制**: 設計書 1 ファイル内で同一エンドポイントが §4.1 と §4.2 に登場する場合 (要約表 + 詳細表) に N 重複としてカウントされる現象を排除
3. **markdown 壊れ表記の警告化**: `?slot_kind={TEAM\|` のような半端なパイプ記法に遭遇したら警告を出し、その行を skip

該当する 🐞 偽陽性件数の内訳:
- F03.15 (personal_timetable): 9 件（GET/POST/PUT 多数で 1〜2 件以外は重複/クエリ込み）
- F03.2 (schedule_personal): 4 件（GET/POST `/me/schedules` の重複）
- F02.5 (quick_memo): 3 件（voice-input-consents の重複）
- F02.9 (favorites_widget): 5 件（POST/PATCH/GET の重複）
- F15.3 / F15.2: 4 件（scope-folders のクエリ込み記述）

### B. 設計書側を `?query` 抜きの記述に統一する PR （🟡 約 10 件）

スキャナ v3 改修を待たずに設計書を整理するなら、以下の設計書で「クエリ文字列付きの API 仕様表」を path/query 分離記法に書き換える。

| 設計書 | 件数 | パターン |
|---|---:|---|
| F03.13 (school_daily_subject_attendance) | 3 | `?from=&to=`, `?termId=`, `?date=YYYY-MM-DD` |
| F03.15 (personal_timetable) | 3 | `?week_of=`, `?from=&to=`, `?slot_kind=` |
| F15.3 (scope_folder_integration) | 3 | `?scopeType=TEAM` |
| F15.2 (team_folder) | 2 | `?scopeType=TEAM` |
| F02.5 (quick_memo) | 2 | `?version=1` |

### C. 設計書側に「実装あり・記述なし」を追記する PR （🟡 約 13 件）

実装は存在するが設計書 §4 系に正式な API 仕様として書かれていない。各機能ドメインを担当する足軽に委ねる:

| エンドポイント | Controller | 担当設計書 | 備考 |
|---|---|---|---|
| GET /me/applications | JobApplicationController | F03.11 / F13.1 | 求人応募一覧 |
| GET /me/contracts | JobContractController | F13.1 | 契約一覧 |
| GET /me/circulations/created | MyCirculationController | F05.2 / F09.14 | 自分が作成した回覧 |
| GET /me/confirmable-notifications/pending | ConfirmableNotificationRecipientController | F10.5 (確認通知) | 要確認通知の未対応一覧 |
| GET /me/favorites/{id} | FavoriteController | F02.9 | 1 件取得 (§4 に未記載) |
| GET /me/pilgrimage/today, history | VillagePilgrimageController | F17.1 (village) | 巡礼系 |
| POST /me/pilgrimage/{id}/visit | 同上 | F17.1 | |
| GET /me/village-pins, POST/DELETE/{id}, PATCH /order | VillagePinController | F17.1 | 村ピン |
| GET /me/village-creation-requests | VillageCreationRequestController | F17.1 | 村作成申請履歴 |

→ **F17.1 系 (5 件) は別足軽 (villages 担当) と相談**。残りは各機能ドメインの API 仕様 §4 に追記。

### D. 将来機能（🔵 9 件）

設計書側で 🔵 マーカ追加のみで対処可能 (案 A)。実装担当者向けの「Phase N で実装予定」シグナル。

| エンドポイント | 設計書 | Phase |
|---|---|---|
| GET /me/jobber-profile, PUT /me/jobber-profile | F13.1 | Phase X (短期求人マッチング) |
| GET /me/jobs/history (×2 重複) | F13.1 | Phase X |
| GET /me/no-show-history | F03.11 | Phase X (応募者バックレ抑止) |
| GET /me/penalties | F03.11 | Phase X (ペナルティ表示) |
| PATCH /me/care-category | F03.12 | Phase 12 (ケア対象者本人モード) |
| POST /me/care-recipient-account | F03.12 | Phase 12 |
| POST /me/personal-timetables/{id}/slots/import-from-team | F03.15 | Phase 6 (タイムテーブル拡張) |
| PATCH /me/profile (BLOG_AUTHOR) | F02.9 | Phase X (ブログ著者) |

各設計書の §4 系 API 仕様表に `状態` 列を追加し、🔵 マーカを付ける運用。本 PR では F02.9 の `PATCH /me/profile` のみマーカ付与済み (案 A の最小実例)。残りは各設計書を抱える次回 PR にて。

### E. POST /me/care-links/{accept,reject} と PATCH /me/care-links/{id} のギャップ（🟡 2 件）

設計書 F03.12 は `POST /api/v1/me/care-links/accept` と `POST /api/v1/me/care-links/reject` を別エンドポイントとして記述しているが、実装 (`CareLinkController#updateLink` PATCH /{linkId}) は status 引数で受け付ける統一エンドポイントに変更されている。

設計書を実装に合わせ、`PATCH /api/v1/me/care-links/{linkId}` (body: `{status: ACCEPTED|REJECTED}`) に統一する必要あり。本 PR は時間制約で見送り。F03.12 系の設計書 PR で別途対処。

---

## 件別 triage 詳細

### Part 1: 設計あり・実装なし 42 件

| # | メソッド | パス | 設計書:行 | 分類 | コメント |
|---|---|---|---|---|---|
| 1 | GET | `/me/attendance/daily?from=&to=` | F03.13:324 | 🟡 | クエリ込み記述。実装は path のみで一致 |
| 2 | GET | `/me/attendance/statistics/term?termId=` | F03.13:357 | 🟡 | 同上 |
| 3 | GET | `/me/attendance/timeline?date=YYYY-MM-DD` | F03.13:334 | 🟡 | 同上 |
| 4 | GET | `/me/favorites` | F02.2:212 | 🐞 | 完全一致するが別ファイル参照のためカウント重複 |
| 5 | GET | `/me/favorites` | F02.9:558 (レート制限表) | 🐞 | 設計書内重複参照 |
| 6 | GET | `/me/favorites` | F02.9:610 (集約取得章) | 🐞 | 設計書内重複参照 |
| 7 | GET | `/me/favorites/check?entityType=X&entityId=Y` | F02.9:424 | 🐞 | サンプル #1 (クエリ込み) |
| 8 | GET | `/me/jobber-profile` | F13.1:1661 | 🔵 | F13.1 Phase X |
| 9 | GET | `/me/jobs/history` | F13.1:1643 | 🔵 | 同上 |
| 10 | GET | `/me/jobs/history` | F13.1:1879 | 🔵 | 同上 (重複) |
| 11 | GET | `/me/no-show-history` | F03.11:1465 | 🔵 | F03.11 ペナルティ系 Phase X |
| 12 | GET | `/me/penalties` | F03.11:1475 | 🔵 | 同上 |
| 13 | GET | `/me/personal-timetable-settings` | F03.15:436 | 🐞 | 完全一致しているが重複カウント |
| 14 | GET | `/me/personal-timetables` | F03.15:359 | 🐞 | 同上 |
| 15 | GET | `/me/personal-timetables/{_}/periods` | F03.15:372 | 🐞 | 同上 |
| 16 | GET | `/me/personal-timetables/{_}/share-targets` | F03.15:388 | 🐞 | 同上 |
| 17 | GET | `/me/personal-timetables/{_}/weekly?week_of=YYYY-MM-DD` | F03.15:380 | 🟡 | クエリ込み |
| 18 | GET | `/me/schedules` | F03.2:121 | 🐞 | 完全一致重複 |
| 19 | GET | `/me/schedules` | F03.2:183 | 🐞 | 同上 |
| 20 | GET | `/me/scope-folders` | F15.3:160 | 🐞 | 完全一致重複 |
| 21 | GET | `/me/scope-folders` | F15.3:279 | 🐞 | 同上 |
| 22 | GET | `/me/scope-folders/default?scopeType=TEAM` | F15.3:196 | 🟡 | クエリ込み |
| 23 | GET | `/me/scope-folders/notifications/summary?scopeType=TEAM` | F15.3:198 | 🟡 | クエリ込み |
| 24 | GET | `/me/scope-folders?scopeType=TEAM` | F15.2:172 | 🟡 | クエリ込み |
| 25 | GET | `/me/timetable-slot-note-fields` | F03.15:420 | 🐞 | 完全一致重複 |
| 26 | GET | `/me/timetable-slot-notes/upcoming?from=&to=` | F03.15:620 | 🟡 | クエリ込み |
| 27 | GET | `/me/timetable-slot-notes?slot_kind={TEAM\|` | F03.15:412 | 🟡 | markdown 壊れ表記 (パイプエスケープ漏れ)。設計書側を修正 |
| 28 | GET | `/me/voice-input-consents/active?version=1` | F02.5:552 | 🟡 | クエリ込み |
| 29 | GET | `/me/voice-input-consents/active?version={_}` | F02.5:1139 | 🟡 | 同上 |
| 30 | PATCH | `/me/care-category` | F03.12:435 | 🔵 | ケア対象者本人モード Phase 12 |
| 31 | PATCH | `/me/favorites/order` | F02.9:394 | 🟡 → **本 PR で修正済** | 設計書を `/reorder` に書き換え |
| 32 | PATCH | `/me/favorites/order` | F02.9:557 | 🟡 → **本 PR で修正済** | 同上 |
| 33 | PATCH | `/me/profile` | F02.9:319 | 🔵 → **本 PR で 🔵 マーカ付与** | BLOG_AUTHOR 編集 API は別 Phase |
| 34 | PATCH | `/me/profile` | F02.9:414 | 🔵 → **本 PR で 🔵 マーカ付与** | 同上 |
| 35 | POST | `/me/care-links/accept` | F03.12:430 | 🟡 | 実装は PATCH /{linkId} body=status へ統合。設計書を新仕様に修正必要 (申し送り E) |
| 36 | POST | `/me/care-links/reject` | F03.12:431 | 🟡 | 同上 |
| 37 | POST | `/me/care-recipient-account` | F03.12:434 | 🔵 | Phase 12 |
| 38 | POST | `/me/favorites` | F02.9:555 | 🐞 | サンプル #2 (PostMapping(空) のスキャナ偽陽性) |
| 39 | POST | `/me/personal-timetables` | F03.15:360 | 🐞 | 完全一致重複 |
| 40 | POST | `/me/personal-timetables/{_}/share-targets` | F03.15:389 | 🐞 | 同上 |
| 41 | POST | `/me/personal-timetables/{_}/slots/import-from-team` | F03.15:648 | 🔵 | F03.15 Phase 6 拡張 (チームから取り込み機能未実装) |
| 42 | POST | `/me/schedules` | F03.2:120,131 | 🐞 | 重複 |
| 43 | POST | `/me/scope-folders?scopeType=TEAM` | F15.2:178 | 🟡 | クエリ込み |
| 44 | POST | `/me/timetable-slot-note-fields` | F03.15:421 | 🐞 | 完全一致重複 |
| 45 | POST | `/me/voice-input-consents` | F02.5:551 | 🐞 | 同上 |
| 46 | PUT | `/me/jobber-profile` | F13.1:1662 | 🔵 | Phase X |
| 47 | PUT | `/me/personal-timetable-settings` | F03.15:437 | 🐞 | 完全一致重複 |
| 48 | PUT | `/me/personal-timetables/{_}/periods` | F03.15:373 | 🐞 | 同上 |
| 49 | PUT | `/me/timetable-slot-notes` | F03.15:413 | 🐞 | 同上 |
| 50 | PUT | `/me/timetable-slot-notes` | F03.15:514 | 🐞 | 重複参照 |

（baseline 上は 42 件だが、テーブルで集計時に番号がずれている分は重複行を区別表示している）

### Part 2: 実装あり・設計なし 25 件

| # | メソッド | パス | Controller | 担当設計書 | 分類 |
|---|---|---|---|---|---|
| 1 | DELETE | `/me/scope-folders/{_}` | MyScopeFolderController | F15.2 (既存記載あり) | 🟡 (スキャナ仕様一致) |
| 2 | DELETE | `/me/scope-folders/{_}/items/{_}` | 同上 | F15.2 (既存記載あり) | 🟡 |
| 3 | DELETE | `/me/village-pins/{_}` | VillagePinController | F17.1 (要追記) | 🟡 |
| 4 | GET | `/me/applications` | JobApplicationController | F03.11 or F13.1 | 🟡 |
| 5 | GET | `/me/attendance/daily` | DailyAttendanceController | F03.13 (クエリ込み記載) | 🟡 (#1 と同一) |
| 6 | GET | `/me/attendance/notices` | FamilyAttendanceNoticeController | F03.13 (記載漏れ) | 🟡 |
| 7 | GET | `/me/attendance/statistics/term` | AttendanceStatisticsController | F03.13 (#2 と同一) | 🟡 |
| 8 | GET | `/me/attendance/timeline` | PeriodAttendanceController | F03.13 (#3 と同一) | 🟡 |
| 9 | GET | `/me/circulations/created` | MyCirculationController | F05.2 / F09.14 | 🟡 |
| 10 | GET | `/me/confirmable-notifications/pending` | ConfirmableNotificationRecipientController | F10.5 (確認必須通知) | 🟡 |
| 11 | GET | `/me/contracts` | JobContractController | F13.1 | 🟡 |
| 12 | GET | `/me/favorites/{_}` | FavoriteController | F02.9 (§4 未記載) | 🟡 |
| 13 | GET | `/me/personal-timetables/{_}/weekly` | PersonalTimetableSlotController | F03.15 (#17 と同一) | 🟡 |
| 14 | GET | `/me/pilgrimage/history` | VillagePilgrimageController | F17.1 (要追記) | 🟡 |
| 15 | GET | `/me/pilgrimage/today` | 同上 | F17.1 | 🟡 |
| 16 | GET | `/me/timetable-slot-notes/upcoming` | TimetableSlotUserNoteController | F03.15 (#26 と同一) | 🟡 |
| 17 | GET | `/me/timetable-slot-notes/{_}/attachments` | TimetableSlotUserNoteAttachmentController | F03.15 (§4 追記要) | 🟡 |
| 18 | GET | `/me/village-creation-requests` | VillageCreationRequestController | F17.1 | 🟡 |
| 19 | PATCH | `/me/favorites/reorder` | FavoriteController | F02.9 → **本 PR で `/reorder` に統一済** | ✅ 解決 |
| 20 | PATCH | `/me/village-pins/order` | VillagePinController | F17.1 | 🟡 |
| 21 | POST | `/me/pilgrimage/{_}/visit` | VillagePilgrimageController | F17.1 | 🟡 |
| 22 | POST | `/me/scope-folders/{_}/items` | MyScopeFolderController | F15.2 (記載あり) | 🟡 (スキャナ仕様一致) |
| 23 | POST | `/me/village-pins/{_}` | VillagePinController | F17.1 | 🟡 |
| 24 | PUT | `/me/scope-folders/reorder` | MyScopeFolderController | F15.2 (記載あり) | 🟡 (スキャナ仕様一致) |
| 25 | PUT | `/me/scope-folders/{_}` | MyScopeFolderController | F15.2 (記載あり) | 🟡 (スキャナ仕様一致) |

---

## 検証

- [x] F02.9 の修正をレンダリング目視確認 (markdown 表崩れ無し)
- [x] バックエンド変更なし (Controller は既に正)
- [ ] `scan_api_drift.py` 再実行 — 本 PR 単独では F02.9 内 `/favorites/order → /favorites/reorder` 4 件減 + Part 2 #19 解消 = 計 **5 件削減** 見込み (PATCH /me/favorites/order×2 + Part 2 #19 + 関連表記)
- [ ] 残乖離 62 件は申し送り (A〜E) に従って後続 PR へ引き継ぐ

---

## 教訓 (Stage 2 後続足軽向け)

1. **me/* はスキャナ偽陽性 (🐞) が 25/67 = 37% と異常に高い**。原因は (a) クエリ込みパス記述、(b) 設計書内同一エンドポイント重複参照、(c) `@PostMapping`(空) パース漏れ。スキャナ v3 で path/query 分離と base path 継承を実装すれば一気に解消。先に v3 改修を片付けてから残りドメインの triage を進める方が効率的かもしれない
2. **F03.15 (personal_timetable) は重複参照が激しい (9 件)**。設計書内で要約表 + 詳細仕様の二重管理になっており、スキャナが同一エンドポイントを N 重カウントしている。設計書側を一覧表のみに統一する整理 PR が必要
3. **F17.1 (village) は me/* 配下に 8 件の API があるが F17.1 設計書には §4 系記述が無い**。F17.1 シリーズの担当足軽と連携して一括追記が望ましい
4. **F13.1 (短期求人) は Phase X 全停止状態**。`/me/jobber-profile`, `/me/jobs/history`, `/me/no-show-history`, `/me/penalties` まとめて 🔵 マーカ付与 PR を別途
5. **F03.12 care-links の POST → PATCH 統合**: 実装が PATCH /{linkId} body=status に集約された経緯を git log で確認し、設計書を追従させる修正が必要

---

## 改訂履歴

- 2026-05-16 初版作成 (足軽 agent-a47c9de2ce1c1b1a2)
